/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ui.actions;

import org.eclipse.ui.IWorkbenchSite;
import org.eclipse.ui.help.WorkbenchHelp;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;

import org.eclipse.jdt.internal.ui.IJavaHelpContextIds;
import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.JavaPluginImages;
import org.eclipse.jdt.internal.ui.javaeditor.JavaEditor;
import org.eclipse.jdt.internal.ui.search.JavaSearchQuery;
import org.eclipse.jdt.internal.ui.search.JavaSearchOperation;
import org.eclipse.jdt.internal.ui.search.PrettySignature;
import org.eclipse.jdt.internal.ui.search.SearchMessages;

/**
 * Finds declarations of the selected element in the workspace.
 * The action is applicable to selections representing a Java element.
 * 
 * <p>
 * This class may be instantiated; it is not intended to be subclassed.
 * </p>
 * 
 * @since 2.0
 */
public class FindDeclarationsAction extends FindAction {

	/**
	 * Creates a new <code>FindDeclarationsAction</code>. The action requires
	 * that the selection provided by the site's selection provider is of type <code>
	 * org.eclipse.jface.viewers.IStructuredSelection</code>.
	 * 
	 * @param site the site providing context information for this action
	 */
	public FindDeclarationsAction(IWorkbenchSite site) {
		this(site, SearchMessages.getString("Search.FindDeclarationAction.label"), new Class[] {IField.class, IMethod.class, IType.class, ICompilationUnit.class, IPackageDeclaration.class, IImportDeclaration.class, IPackageFragment.class, ILocalVariable.class }); //$NON-NLS-1$
		setToolTipText(SearchMessages.getString("Search.FindDeclarationAction.tooltip")); //$NON-NLS-1$
	}

	/**
	 * Note: This constructor is for internal use only. Clients should not call this constructor.
	 */
	public FindDeclarationsAction(JavaEditor editor) {
		this(editor, SearchMessages.getString("Search.FindDeclarationAction.label"), new Class[] {IField.class, IMethod.class, IType.class, ICompilationUnit.class, IPackageDeclaration.class, IImportDeclaration.class, IPackageFragment.class, ILocalVariable.class}); //$NON-NLS-1$
		setToolTipText(SearchMessages.getString("Search.FindDeclarationAction.tooltip")); //$NON-NLS-1$
	}

	FindDeclarationsAction(IWorkbenchSite site, String label, Class[] validTypes) {
		super(site, label, validTypes);
		setImageDescriptor(JavaPluginImages.DESC_OBJS_SEARCH_DECL);
		WorkbenchHelp.setHelp(this, IJavaHelpContextIds.FIND_DECLARATIONS_IN_WORKSPACE_ACTION);
	}

	FindDeclarationsAction(JavaEditor editor, String label, Class[] validTypes) {
		super(editor, label, validTypes);
		setImageDescriptor(JavaPluginImages.DESC_OBJS_SEARCH_DECL);
		WorkbenchHelp.setHelp(this, IJavaHelpContextIds.FIND_DECLARATIONS_IN_WORKSPACE_ACTION);
	}
	
	JavaSearchOperation makeOperation(IJavaElement element) throws JavaModelException {
		if (element.getElementType() == IJavaElement.METHOD) {
			IMethod method= (IMethod)element;
			int searchFor= IJavaSearchConstants.METHOD;
			if (method.isConstructor())
				searchFor= IJavaSearchConstants.CONSTRUCTOR;
			IType type= getType(element);
			String pattern= PrettySignature.getUnqualifiedMethodSignature(method);
			return new JavaSearchOperation(JavaPlugin.getWorkspace(), pattern, true,
				searchFor, getLimitTo(), getScope(type), getScopeDescription(type), getCollector());
		}
		else
			return super.makeOperation(element);
	}

	int getLimitTo() {
		return IJavaSearchConstants.DECLARATIONS;
	}
	
	protected JavaSearchQuery createJob(IJavaElement element) throws JavaModelException {
		if (element.getElementType() == IJavaElement.METHOD) {
			IMethod method= (IMethod)element;
			int searchFor= IJavaSearchConstants.METHOD;
			if (method.isConstructor())
				searchFor= IJavaSearchConstants.CONSTRUCTOR;
			IType type= getType(element);
			String pattern= PrettySignature.getUnqualifiedMethodSignature(method);
			return new JavaSearchQuery(searchFor, getLimitTo(), pattern, true, getScope(type), getScopeDescription(type));
		}
		else
			return super.createJob(element);
	}

}
