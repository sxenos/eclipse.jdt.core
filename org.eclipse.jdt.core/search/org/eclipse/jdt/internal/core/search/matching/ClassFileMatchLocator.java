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
package org.eclipse.jdt.internal.core.search.matching;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.search.IJavaSearchResultCollector;
import org.eclipse.jdt.internal.compiler.env.*;
import org.eclipse.jdt.internal.compiler.lookup.*;
import org.eclipse.jdt.internal.compiler.problem.AbortCompilation;
import org.eclipse.jdt.internal.core.*;
import org.eclipse.jdt.internal.core.search.indexing.IIndexConstants;

public class ClassFileMatchLocator implements IIndexConstants {

public static char[] convertClassFileFormat(char[] name) {
	for (int i = 0, l = name.length; i < l; i++) {
		if (name[i] == '/') {
			char[] newName = (char[]) name.clone();
			CharOperation.replace(newName, '/', '.');
			return newName;
		}
	}
	return name;
}

boolean checkDeclaringType(IBinaryType enclosingBinaryType, char[] simpleName, char[] qualification, boolean isCaseSensitive) {
	if (simpleName == null && qualification == null) return true;
	if (enclosingBinaryType == null) return true;

	char[] declaringTypeName = convertClassFileFormat(enclosingBinaryType.getName());
	return checkTypeName(simpleName, qualification, declaringTypeName, isCaseSensitive);
}
boolean checkParameters(char[] methodDescriptor, char[][] parameterSimpleNames, char[][] parameterQualifications, boolean isCaseSensitive) {
	char[][] arguments = Signature.getParameterTypes(methodDescriptor);
	int parameterCount = parameterSimpleNames.length;
	if (parameterCount != arguments.length) return false;
	for (int i = 0; i < parameterCount; i++)
		if (!checkTypeName(parameterSimpleNames[i], parameterQualifications[i], Signature.toCharArray(arguments[i]), isCaseSensitive))
			return false;
	return true;
}
boolean checkTypeName(char[] simpleName, char[] qualification, char[] fullyQualifiedTypeName, boolean isCaseSensitive) {
	// NOTE: if case insensitive then simpleName & qualification are assumed to be lowercase
	char[] wildcardPattern;
	if (simpleName == null) {
		if (qualification == null) return true;
		wildcardPattern = CharOperation.concat(qualification, ONE_STAR, '.');
	} else {
		wildcardPattern = qualification == null
			? CharOperation.concat(ONE_STAR, simpleName)
			: CharOperation.concat(qualification, simpleName, '.');
	}
	return CharOperation.match(wildcardPattern, fullyQualifiedTypeName, isCaseSensitive);
}
/**
 * Locate declaration in the current class file. This class file is always in a jar.
 */
public void locateMatches(MatchLocator locator, ClassFile classFile, IBinaryType info) throws CoreException {
	// check class definition
	SearchPattern pattern = locator.pattern;
	BinaryType binaryType = (BinaryType) classFile.getType();
	if (matchBinary(pattern, info, null))
		locator.reportBinaryMatch(binaryType, info, IJavaSearchResultCollector.EXACT_MATCH);

	int accuracy = IJavaSearchResultCollector.EXACT_MATCH;
	if (pattern.mustResolve) {
		try {
			BinaryTypeBinding binding = locator.cacheBinaryType(binaryType);
			if (binding != null) {
				// filter out element not in hierarchy scope
				if (!locator.typeInHierarchy(binding)) return;

				MethodBinding[] methods = binding.methods();
				for (int i = 0, l = methods.length; i < l; i++) {
					MethodBinding method = methods[i];
					if (locator.patternLocator.resolveLevel(method) == PatternLocator.ACCURATE_MATCH) {
						IMethod methodHandle = binaryType.getMethod(
							new String(method.isConstructor() ? binding.compoundName[binding.compoundName.length-1] : method.selector),
							CharOperation.toStrings(Signature.getParameterTypes(convertClassFileFormat(method.signature()))));
						locator.reportBinaryMatch(methodHandle, info, IJavaSearchResultCollector.EXACT_MATCH);
					}
				}

				FieldBinding[] fields = binding.fields();
				for (int i = 0, l = fields.length; i < l; i++) {
					FieldBinding field = fields[i];
					if (locator.patternLocator.resolveLevel(field) == PatternLocator.ACCURATE_MATCH) {
						IField fieldHandle = binaryType.getField(new String(field.name));
						locator.reportBinaryMatch(fieldHandle, info, IJavaSearchResultCollector.EXACT_MATCH);
					}
				}

				// no need to check binary info since resolve was successful
				return;
			}
		} catch (AbortCompilation e) { // if compilation was aborted it is a problem with the class path
		}
		// report as a potential match if binary info matches the pattern		
		accuracy = IJavaSearchResultCollector.POTENTIAL_MATCH;
	}

	IBinaryMethod[] methods = info.getMethods();
	if (methods != null) {
		for (int i = 0, l = methods.length; i < l; i++) {
			IBinaryMethod method = methods[i];
			if (matchBinary(pattern, method, info)) {
				IMethod methodHandle = binaryType.getMethod(
					new String(method.isConstructor() ? info.getName() : method.getSelector()),
					CharOperation.toStrings(Signature.getParameterTypes(convertClassFileFormat(method.getMethodDescriptor()))));
				locator.reportBinaryMatch(methodHandle, info, accuracy);
			}
		}
	}

	IBinaryField[] fields = info.getFields();
	if (fields != null) {
		for (int i = 0, l = fields.length; i < l; i++) {
			IBinaryField field = fields[i];
			if (matchBinary(pattern, field, info)) {
				IField fieldHandle = binaryType.getField(new String(field.getName()));
				locator.reportBinaryMatch(fieldHandle, info, accuracy);
			}
		}
	}
}
/**
 * Finds out whether the given binary info matches the search pattern.
 * Default is to return false.
 */
boolean matchBinary(SearchPattern pattern, Object binaryInfo, IBinaryType enclosingBinaryType) {
	switch (pattern.kind) {
		case CONSTRUCTOR_PATTERN :
			return matchConstructor((ConstructorPattern) pattern, binaryInfo, enclosingBinaryType);
		case FIELD_PATTERN :
			return matchField((FieldPattern) pattern, binaryInfo, enclosingBinaryType);
		case METHOD_PATTERN :
			return matchMethod((MethodPattern) pattern, binaryInfo, enclosingBinaryType);
		case SUPER_REF_PATTERN :
			return matchSuperTypeReference((SuperTypeReferencePattern) pattern, binaryInfo, enclosingBinaryType);
		case TYPE_DECL_PATTERN :
			return matchTypeDeclaration((TypeDeclarationPattern) pattern, binaryInfo, enclosingBinaryType);
		case OR_PATTERN :
			SearchPattern[] patterns = ((OrPattern) pattern).patterns;
			for (int i = 0, length = patterns.length; i < length; i++)
				if (matchBinary(patterns[i], binaryInfo, enclosingBinaryType)) return true;
	}
	return false;
}
boolean matchConstructor(ConstructorPattern pattern, Object binaryInfo, IBinaryType enclosingBinaryType) {
	if (!pattern.findDeclarations) return false; // only relevant when finding declarations
	if (!(binaryInfo instanceof IBinaryMethod)) return false;

	IBinaryMethod method = (IBinaryMethod) binaryInfo;
	if (!method.isConstructor()) return false;
	if (!checkDeclaringType(enclosingBinaryType, pattern.declaringSimpleName, pattern.declaringQualification, pattern.isCaseSensitive))
		return false;
	if (pattern.parameterSimpleNames != null) {
		char[] methodDescriptor = convertClassFileFormat(method.getMethodDescriptor());
		if (!checkParameters(methodDescriptor, pattern.parameterSimpleNames, pattern.parameterQualifications, pattern.isCaseSensitive))
			return false;
	}
	return true;
}
boolean matchField(FieldPattern pattern, Object binaryInfo, IBinaryType enclosingBinaryType) {
	if (!pattern.findDeclarations) return false; // only relevant when finding declarations
	if (!(binaryInfo instanceof IBinaryField)) return false;

	IBinaryField field = (IBinaryField) binaryInfo;
	if (!pattern.matchesName(pattern.name, field.getName())) return false;
	if (!checkDeclaringType(enclosingBinaryType, pattern.declaringSimpleName, pattern.declaringQualification, pattern.isCaseSensitive))
		return false;

	char[] fieldTypeSignature = Signature.toCharArray(convertClassFileFormat(field.getTypeName()));
	return checkTypeName(pattern.typeSimpleName, pattern.typeQualification, fieldTypeSignature, pattern.isCaseSensitive);
}
boolean matchMethod(MethodPattern pattern, Object binaryInfo, IBinaryType enclosingBinaryType) {
	if (!pattern.findDeclarations) return false; // only relevant when finding declarations
	if (!(binaryInfo instanceof IBinaryMethod)) return false;

	IBinaryMethod method = (IBinaryMethod) binaryInfo;
	if (!pattern.matchesName(pattern.selector, method.getSelector())) return false;
	if (!checkDeclaringType(enclosingBinaryType, pattern.declaringSimpleName, pattern.declaringQualification, pattern.isCaseSensitive))
		return false;

	// look at return type only if declaring type is not specified
	boolean checkReturnType = pattern.declaringSimpleName == null && (pattern.returnSimpleName != null || pattern.returnQualification != null);
	boolean checkParameters = pattern.parameterSimpleNames != null;
	if (checkReturnType || checkParameters) {
		char[] methodDescriptor = convertClassFileFormat(method.getMethodDescriptor());
		if (checkReturnType) {
			char[] returnTypeSignature = Signature.toCharArray(Signature.getReturnType(methodDescriptor));
			if (!checkTypeName(pattern.returnSimpleName, pattern.returnQualification, returnTypeSignature, pattern.isCaseSensitive))
				return false;
		}
		if (checkParameters &&  !checkParameters(methodDescriptor, pattern.parameterSimpleNames, pattern.parameterQualifications, pattern.isCaseSensitive))
			return false;
	}
	return true;
}
boolean matchSuperTypeReference(SuperTypeReferencePattern pattern, Object binaryInfo, IBinaryType enclosingBinaryType) {
	if (!(binaryInfo instanceof IBinaryType)) return false;

	IBinaryType type = (IBinaryType) binaryInfo;
	if (!pattern.checkOnlySuperinterfaces) {
		char[] vmName = type.getSuperclassName();
		if (vmName != null) {
			char[] superclassName = convertClassFileFormat(vmName);
			if (checkTypeName(pattern.superSimpleName, pattern.superQualification, superclassName, pattern.isCaseSensitive))
				return true;
		}
	}

	char[][] superInterfaces = type.getInterfaceNames();
	if (superInterfaces != null) {
		for (int i = 0, max = superInterfaces.length; i < max; i++) {
			char[] superInterfaceName = convertClassFileFormat(superInterfaces[i]);
			if (checkTypeName(pattern.superSimpleName, pattern.superQualification, superInterfaceName, pattern.isCaseSensitive))
				return true;
		}
	}
	return false;
}
boolean matchTypeDeclaration(TypeDeclarationPattern pattern, Object binaryInfo, IBinaryType enclosingBinaryType) {
	if (!(binaryInfo instanceof IBinaryType)) return false;

	IBinaryType type = (IBinaryType) binaryInfo;
	char[] fullyQualifiedTypeName = convertClassFileFormat(type.getName());
	if (pattern.enclosingTypeNames == null || pattern instanceof QualifiedTypeDeclarationPattern) {
		if (!checkTypeName(pattern.simpleName, pattern.pkg, fullyQualifiedTypeName, pattern.isCaseSensitive)) return false;
	} else {
		char[] enclosingTypeName = CharOperation.concatWith(pattern.enclosingTypeNames, '.');
		char[] patternString = pattern.pkg == null
			? enclosingTypeName
			: CharOperation.concat(pattern.pkg, enclosingTypeName, '.');
		if (!checkTypeName(pattern.simpleName, patternString, fullyQualifiedTypeName, pattern.isCaseSensitive)) return false;
	}

	switch (pattern.classOrInterface) {
		case CLASS_SUFFIX:
			return !type.isInterface();
		case INTERFACE_SUFFIX:
			return type.isInterface();
		case TYPE_SUFFIX: // nothing
	}
	return true;
}
}
