/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.corext.refactoring.nls;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubProgressMonitor;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaModelStatusConstants;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.JavaModelException;

import org.eclipse.jdt.internal.corext.Assert;
import org.eclipse.jdt.internal.corext.SourceRange;
import org.eclipse.jdt.internal.corext.refactoring.Checks;
import org.eclipse.jdt.internal.corext.refactoring.base.JavaStringStatusContext;
import org.eclipse.jdt.internal.corext.refactoring.changes.DynamicValidationStateChange;
import org.eclipse.jdt.internal.corext.refactoring.util.ResourceUtil;

import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.RefactoringStatusContext;

public class NLSRefactoring extends Refactoring {

	public static final String BUNDLE_NAME= "BUNDLE_NAME"; //$NON-NLS-1$
	public static final String PROPERTY_FILE_EXT= ".properties"; //$NON-NLS-1$
	public static final String DEFAULT_ACCESSOR_CLASSNAME= "Messages"; //$NON-NLS-1$
	public static final String KEY= "${key}"; //$NON-NLS-1$

	private static final String DEFAULT_PROPERTY_FILENAME= "messages"; //$NON-NLS-1$

	//private IPath fPropertyFilePath;

	private String fAccessorClassName;
	private IPackageFragment fAccessorPackage;
	private String fResourceBundleName;
	private IPackageFragment fResourceBundlePackage;

	private String fSubstitutionPattern;
	private ICompilationUnit fCu;
	private NLSSubstitution[] fSubstitutions;

	private NLSRefactoring(ICompilationUnit cu) {
		Assert.isNotNull(cu);

		fCu= cu;
		NLSInfo nlsInfo= new NLSInfo(cu);
		fSubstitutions= NLSHolder.create(cu, nlsInfo);
		NLSHint nlsHint= new NLSHint(fSubstitutions, cu, nlsInfo);

		fAccessorClassName= nlsHint.getMessageClass();
		fAccessorPackage= nlsHint.getMessageClassPackage();
		fResourceBundleName= nlsHint.getResourceBundle();
		fResourceBundlePackage= nlsHint.getResourceBundlePackage();

		fSubstitutionPattern= getDefaultSubstitutionPattern(fAccessorClassName);
	}

	public static NLSRefactoring create(ICompilationUnit cu) {
		if (!isAvailable(cu))
			return null;
		return new NLSRefactoring(cu);
	}

	public static boolean isAvailable(ICompilationUnit cu) {
		if (cu == null)
			return false;

		if (!cu.exists())
			return false;

		return true;
	}

	/**
	 * no validation is done
	 * 
	 * @param pattern
	 *            Example: "Messages.getString(${key})". Must not be
	 *            <code>null</code>. should (but does not have to) contain
	 *            NLSRefactoring.KEY (default value is $key$) only the first
	 *            occurrence of this key will be used
	 */
	public void setSubstitutionPattern(String pattern) {
		Assert.isNotNull(pattern);
		fSubstitutionPattern= pattern;
	}

	/**
	 * to show the pattern in the ui
	 */
	public String getSubstitutionPattern() {
		if (fSubstitutionPattern == null) {
			return getDefaultSubstitutionPattern(fAccessorClassName);
		}
		return fSubstitutionPattern;
	}

	public static String getDefaultSubstitutionPattern(String accessorName) {
		return accessorName + ".getString(" + KEY + ")"; //$NON-NLS-2$ //$NON-NLS-1$
	}

	public ICompilationUnit getCu() {
		return fCu;
	}

	public String getName() {
		return NLSMessages.getFormattedString("NLSrefactoring.compilation_unit", fCu.getElementName());//$NON-NLS-1$
	}

	public RefactoringStatus checkInitialConditions(IProgressMonitor pm) throws CoreException {

		if (fSubstitutions.length == 0) {
			String message= NLSMessages.getFormattedString("NLSRefactoring.no_strings", fCu.getElementName());//$NON-NLS-1$
			return RefactoringStatus.createFatalErrorStatus(message);
		}
		return new RefactoringStatus();
	}

	public RefactoringStatus checkFinalConditions(IProgressMonitor pm) throws CoreException {
		checkParameters();
		try {

			pm.beginTask(NLSMessages.getString("NLSrefactoring.checking"), 7); //$NON-NLS-1$

			RefactoringStatus result= new RefactoringStatus();

			result.merge(checkIfAnythingToDo());
			if (result.hasFatalError()) {
				return result;
			}
			pm.worked(1);

			result.merge(validateModifiesFiles());
			if (result.hasFatalError()) {
				return result;
			}
			pm.worked(1);

			result.merge(checkSubstitutionPattern());
			pm.worked(1);

			result.merge(checkForDuplicateKeys());
			pm.worked(1);

			result.merge(checkForKeysAlreadyDefined());
			pm.worked(1);

			result.merge(checkKeys());
			pm.worked(1);

			if (!propertyFileExists() && willModifyPropertyFile()) {
				String msg= NLSMessages.getFormattedString("NLSrefactoring.will_be_created", getPropertyFilePath().toString()); //$NON-NLS-1$
				result.addInfo(msg);
			}
			pm.worked(1);

			return result;
		} finally {
			pm.done();
		}
	}

	public Change createChange(IProgressMonitor pm) throws CoreException {
		try {
			checkParameters();

			pm.beginTask("", 3); //$NON-NLS-1$

			final DynamicValidationStateChange result= new DynamicValidationStateChange("NLS Refactoring"); //$NON-NLS-1$

			if (willCreateAccessorClass()) {
				result.add(AccessorClass.create(fCu, fAccessorClassName, getAccessorCUPath(), fAccessorPackage, getPropertyFilePath(), new SubProgressMonitor(pm, 1)));
			}
			pm.worked(1);

			if (willModifySource()) {
				result.add(NLSSourceModifier.create(getCu(), fSubstitutions, fSubstitutionPattern, fAccessorPackage, fAccessorClassName));
			}
			pm.worked(1);

			if (willModifyPropertyFile()) {
				result.add(NLSPropertyFileModifier.create(fSubstitutions, getPropertyFilePath()));
			}
			pm.worked(1);

			return result;
		} finally {
			pm.done();
		}
	}

	private void checkParameters() {
		Assert.isNotNull(fSubstitutions);
		Assert.isNotNull(fAccessorPackage);

		// these values have defaults ...
		Assert.isNotNull(fAccessorClassName);
		Assert.isNotNull(fSubstitutionPattern);
	}

	private IFile[] getAllFilesToModify() {

		List files= new ArrayList(3);
		if (willModifySource()) {
			IFile file= ResourceUtil.getFile(fCu);
			if (file != null)
				files.add(file);
		}

		if (willModifyPropertyFile() && propertyFileExists())
			files.add(getPropertyFile());

		return (IFile[]) files.toArray(new IFile[files.size()]);
	}

	//TODO: not dry..see NLSPropertyFileModifier
	public IFile getPropertyFile() {
		return (IFile) (ResourcesPlugin.getWorkspace().getRoot().findMember(getPropertyFilePath()));
	}

	public IPath getPropertyFilePath() {
		return fResourceBundlePackage.getPath().append(fResourceBundleName);
	}

	private RefactoringStatus validateModifiesFiles() {
		return Checks.validateModifiesFiles(getAllFilesToModify());
	}

	//should stop checking if fatal error
	private RefactoringStatus checkIfAnythingToDo() throws JavaModelException {
		if (willCreateAccessorClass())
			return null;

		if (willModifyPropertyFile())
			return null;

		if (willModifySource())
			return null;

		RefactoringStatus result= new RefactoringStatus();
		result.addFatalError(NLSMessages.getString("NLSrefactoring.nothing_to_do")); //$NON-NLS-1$
		return result;
	}

	private boolean propertyFileExists() {
		return Checks.resourceExists(getPropertyFilePath());
	}

	private RefactoringStatus checkSubstitutionPattern() {
		String pattern= getSubstitutionPattern();

		RefactoringStatus result= new RefactoringStatus();
		if (pattern.trim().length() == 0) {//$NON-NLS-1$ 
			result.addError(NLSMessages.getString("NLSrefactoring.pattern_empty")); //$NON-NLS-1$
		}

		if (pattern.indexOf(KEY) == -1) {
			String msg= NLSMessages.getFormattedString("NLSrefactoring.pattern_does_not_contain", KEY); //$NON-NLS-1$
			result.addWarning(msg);
		}

		if (pattern.indexOf(KEY) != pattern.lastIndexOf(KEY)) {
			String msg= NLSMessages.getFormattedString("NLSrefactoring.Only_the_first_occurrence_of", KEY);//$NON-NLS-1$
			result.addWarning(msg);
		}

		return result;
	}

	private RefactoringStatus checkForKeysAlreadyDefined() throws JavaModelException {
		if (!propertyFileExists())
			return null;
		RefactoringStatus result= new RefactoringStatus();
		PropertyResourceBundle bundle= getPropertyBundle();
		if (bundle == null) {
			return null;
		}

		NLSSubstitution[] subs= fSubstitutions;

		for (int i= 0; i < subs.length; i++) {
			NLSSubstitution substitution= subs[i];
			if ((substitution.getState() == NLSSubstitution.EXTERNALIZED) && substitution.hasStateChanged()) {
				String key= substitution.getKey();
				String s= getBundleString(bundle, key);
				if (s != null) {
					if (!s.equals(substitution.getValue())) {
						String[] args= {key, substitution.getValue()};
						String msg= NLSMessages.getFormattedString("NLSrefactoring.already_exists", args); //$NON-NLS-1$
						result.addFatalError(msg);
					} else {
						String[] args= {key, s};
						String msg= NLSMessages.getFormattedString("NLSrefactoring.already_in_bundle", args); //$NON-NLS-1$
						result.addWarning(msg);
					}
				}
			}
		}
		return result;
	}

	private boolean hasSameValue(String val, NLSSubstitution sub) {
		return (val.equals(sub.getNLSElement().getValue()));
	}

	/**
	 * returns <code>null</code> if not defined
	 */
	private String getBundleString(PropertyResourceBundle bundle, String key) {
		try {
			return bundle.getString(key);
		} catch (MissingResourceException e) {
			return null;
		}
	}

	private PropertyResourceBundle getPropertyBundle() throws JavaModelException {
		InputStream is= getPropertyFileInputStream();
		if (is == null)
			return null;
		try {
			PropertyResourceBundle result= new PropertyResourceBundle(is);
			return result;
		} catch (IOException e1) {
			return null;
		} finally {
			try {
				is.close();
			} catch (IOException e) {
				throw new JavaModelException(e, IJavaModelStatusConstants.IO_EXCEPTION);
			}
		}
	}

	private InputStream getPropertyFileInputStream() throws JavaModelException {
		IFile file= ResourcesPlugin.getWorkspace().getRoot().getFile(getPropertyFilePath());

		try {
			return file.getContents();
		} catch (CoreException e) {
			throw new JavaModelException(e, IJavaModelStatusConstants.CORE_EXCEPTION);
		}
	}

	private RefactoringStatus checkForDuplicateKeys() {
		Map map= new HashMap(); //String (key) -> Set of NLSSubstitution
		NLSSubstitution[] subs= fSubstitutions;
		for (int i= 0; i < subs.length; i++) {
			NLSSubstitution sub= subs[i];
			String key= sub.getKey();
			if (!map.containsKey(key)) {
				map.put(key, new HashSet());
			}
			((Set) map.get(key)).add(sub);
		}

		RefactoringStatus result= new RefactoringStatus();
		for (Iterator iter= map.keySet().iterator(); iter.hasNext();) {
			Set substitutions= (Set) map.get(iter.next());
			result.merge(checkForDuplicateKeys(substitutions));
		}
		return result;
	}

	/**
	 * all elements in the parameter must be NLSSubstitutions with the same key
	 */
	private RefactoringStatus checkForDuplicateKeys(Set subs) {
		if (subs.size() <= 1)
			return null;

		NLSSubstitution[] toTranslate= getEntriesToTranslate(subs);
		if (toTranslate.length <= 1)
			return null;

		String value= toTranslate[0].getNLSElement().getValue();
		for (int i= 0; i < toTranslate.length; i++) {
			NLSSubstitution each= toTranslate[i];
			if (!hasSameValue(value, each)) {
				String msg= NLSMessages.getFormattedString("NLSrefactoring.duplicated", each.getKey());//$NON-NLS-1$
				return RefactoringStatus.createFatalErrorStatus(msg);
			}
		}
		String[] args= {toTranslate[0].getKey(), value};
		String msg= NLSMessages.getFormattedString("NLSrefactoring.reused", args); //$NON-NLS-1$
		return RefactoringStatus.createWarningStatus(msg);
	}

	private static NLSSubstitution[] getEntriesToTranslate(Set subs) {
		List result= new ArrayList(subs.size());
		for (Iterator iter= subs.iterator(); iter.hasNext();) {
			NLSSubstitution each= (NLSSubstitution) iter.next();
			if (each.getState() == NLSSubstitution.EXTERNALIZED)
				result.add(each);
		}
		return (NLSSubstitution[]) result.toArray(new NLSSubstitution[result.size()]);
	}

	private RefactoringStatus checkKeys() {
		RefactoringStatus result= new RefactoringStatus();
		NLSSubstitution[] subs= fSubstitutions;
		for (int i= 0; i < subs.length; i++) {
			NLSSubstitution substitution= subs[i];
			if ((substitution.getState() == NLSSubstitution.EXTERNALIZED) && substitution.hasStateChanged()) {
				result.merge(checkKey(substitution.getKey()));
			}
		}
		return result;
	}

	private static RefactoringStatus checkKey(String key) {
		RefactoringStatus result= new RefactoringStatus();

		if (key == null)
			result.addFatalError(NLSMessages.getString("NLSrefactoring.null")); //$NON-NLS-1$

		if (key.startsWith("!") || key.startsWith("#")) { //$NON-NLS-1$ //$NON-NLS-2$
			RefactoringStatusContext context= new JavaStringStatusContext(key, new SourceRange(0, 0));
			result.addWarning(NLSMessages.getString("NLSRefactoring.warning"), context); //$NON-NLS-1$
		}

		if ("".equals(key.trim())) //$NON-NLS-1$
			result.addFatalError(NLSMessages.getString("NLSrefactoring.empty")); //$NON-NLS-1$

		final String[] UNWANTED_STRINGS= {" ", ":", "\"", "\\", "'", "?", "="}; //$NON-NLS-7$ //$NON-NLS-6$ //$NON-NLS-5$ //$NON-NLS-4$ //$NON-NLS-3$ //$NON-NLS-2$ //$NON-NLS-1$
		//feature in resource bundle - does not work properly if keys have ":"
		for (int i= 0; i < UNWANTED_STRINGS.length; i++) {
			if (key.indexOf(UNWANTED_STRINGS[i]) != -1) {
				String[] args= {key, UNWANTED_STRINGS[i]};
				String msg= NLSMessages.getFormattedString("NLSrefactoring.should_not_contain", args); //$NON-NLS-1$
				result.addError(msg);
			}
		}
		return result;
	}

	private boolean willCreateAccessorClass() throws JavaModelException {

		NLSSubstitution[] subs= fSubstitutions;
		if (NLSSubstitution.countItems(subs, NLSSubstitution.EXTERNALIZED) == 0) {
			return false;
		}

		ICompilationUnit compilationUnit= getAccessorCu();
		if (compilationUnit.exists()) {
			return false;
		}

		if (typeNameExistsInPackage(fAccessorPackage, fAccessorClassName)) {
			return false;
		}

		return (!Checks.resourceExists(getAccessorCUPath()));
	}

	private ICompilationUnit getAccessorCu() {
		return fAccessorPackage.getCompilationUnit(getAccessorCUName());
	}

	private boolean willModifySource() {
		NLSSubstitution[] subs= fSubstitutions;
		for (int i= 0; i < subs.length; i++) {
			if (subs[i].hasSourceChange())
				return true;
		}
		return false;
	}

	private boolean willModifyPropertyFile() {
		NLSSubstitution[] subs= fSubstitutions;
		for (int i= 0; i < subs.length; i++) {
			NLSSubstitution substitution= subs[i];
			if (substitution.hasPropertyFileChange()) {
				if (substitution.hasStateChanged()) {
					if ((substitution.getState() == NLSSubstitution.EXTERNALIZED) || (substitution.getInitialState() == NLSSubstitution.EXTERNALIZED)) {
						return true;
					}
				} else {
					return true;
				}
			}
		}
		return false;
	}

	private boolean typeNameExistsInPackage(IPackageFragment pack, String name) throws JavaModelException {
		return Checks.findTypeInPackage(pack, name) != null;
	}

	private String getAccessorCUName() {
		return fAccessorClassName + ".java"; //$NON-NLS-1$
	}

	private IPath getAccessorCUPath() {
		IPath res= fAccessorPackage.getPath().append(getAccessorCUName());
		return res;
	}

	public void setAccessorClassName(String name) {
		Assert.isNotNull(name);
		fAccessorClassName= name;
	}

	public void setAccessorPackage(IPackageFragment packageFragment) {
		Assert.isNotNull(packageFragment);
		fAccessorPackage= packageFragment;
	}

	public NLSSubstitution[] getSubstitutions() {
		return fSubstitutions;
	}

	public String getPrefixHint() {
		String cuName= fCu.getElementName();
		if (cuName.endsWith(".java")) //$NON-NLS-1$
			return cuName.substring(0, cuName.length() - ".java".length()) + '.'; //$NON-NLS-1$
		return ""; //$NON-NLS-1$
	}


	public static String getDefaultPropertiesFilename() {
		return DEFAULT_PROPERTY_FILENAME + PROPERTY_FILE_EXT;
	}

	public void setResourceBundlePackage(IPackageFragment resourceBundlePackage) {
		fResourceBundlePackage= resourceBundlePackage;
	}

	public void setResourceBundleName(String resourceBundleName) {
		fResourceBundleName= resourceBundleName;
	}

	public void setPropertyFilePath(IPath propertyFilePath) {
	}

	public IPackageFragment getAccessorPackage() {
		return fAccessorPackage;
	}

	public IPackageFragment getResourceBundlePackage() {
		return fResourceBundlePackage;
	}

	public String getAccessorClassName() {
		return fAccessorClassName;
	}

	public String getResourceBundleName() {
		return fResourceBundleName;
	}
}