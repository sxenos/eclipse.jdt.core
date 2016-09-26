/*******************************************************************************
 * Copyright (c) 2015, 2016 Google, Inc and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Stefan Xenos (Google) - Initial implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.core.nd.java.model;

import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModelStatusConstants;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.eclipse.jdt.internal.compiler.env.IBinaryType;
import org.eclipse.jdt.internal.compiler.env.IDependent;
import org.eclipse.jdt.internal.compiler.util.SuffixConstants;
import org.eclipse.jdt.internal.core.ClassFile;
import org.eclipse.jdt.internal.core.JarPackageFragmentRoot;
import org.eclipse.jdt.internal.core.JavaModelManager;
import org.eclipse.jdt.internal.core.PackageFragment;
import org.eclipse.jdt.internal.core.nd.IReader;
import org.eclipse.jdt.internal.core.nd.Nd;
import org.eclipse.jdt.internal.core.nd.db.IndexException;
import org.eclipse.jdt.internal.core.nd.java.JavaIndex;
import org.eclipse.jdt.internal.core.nd.java.JavaNames;
import org.eclipse.jdt.internal.core.nd.java.NdResourceFile;
import org.eclipse.jdt.internal.core.nd.java.NdType;
import org.eclipse.jdt.internal.core.nd.java.TypeRef;
import org.eclipse.jdt.internal.core.nd.util.CharArrayUtils;
import org.eclipse.jdt.internal.core.util.Util;

public class BinaryTypeFactory {
	public static final class NotInIndexException extends Exception {
		private static final long serialVersionUID = 2859848007651528256L;

		public NotInIndexException() {
		}
	}
	
	private final static char[] PACKAGE_INFO = "package-info".toCharArray(); //$NON-NLS-1$

	/**
	 * Returns a descriptor for the given class within the given package fragment, or null if the fragment doesn't have
	 * a location on the filesystem.
	 */
	private static BinaryTypeDescriptor createDescriptor(PackageFragment pkg, ClassFile classFile) {
		String name = classFile.getName();
		IJavaElement root = pkg.getParent();
		IPath location = JavaIndex.getLocationForElement(root);
		String entryName = Util.concatWith(pkg.names, classFile.getElementName(), '/');
		char[] fieldDescriptor = CharArrayUtils.concat(new char[] { 'L' },
				Util.concatWith(pkg.names, name, '/').toCharArray(), new char[] { ';' });
		IPath workspacePath = root.getPath();
		String indexPath;

		if (location == null) {
			return null;
		}

		if (root instanceof JarPackageFragmentRoot) {
			indexPath = root.getHandleIdentifier() + IDependent.JAR_FILE_ENTRY_SEPARATOR + entryName;
		} else {
			location = location.append(entryName);
			indexPath = location.toString();
			workspacePath = classFile.resource().getFullPath();
		}

		return new BinaryTypeDescriptor(location.toString().toCharArray(), fieldDescriptor,
				workspacePath.toString().toCharArray(), indexPath.toCharArray());
	}

	public static BinaryTypeDescriptor createDescriptor(IClassFile classFile) {
		ClassFile concreteClass = (ClassFile)classFile;
		PackageFragment parent = (PackageFragment) classFile.getParent();

		return createDescriptor(parent, concreteClass);
	}

	public static BinaryTypeDescriptor createDescriptor(IType type) {
		return createDescriptor(type.getClassFile());
	}

	public static IBinaryType create(IJavaElement javaElement, String binaryName) {
		return create(JavaIndex.getIndex(), javaElement, binaryName);
	}

	public static IBinaryType create(JavaIndex index, IJavaElement javaElement, String binaryName) {
		IPath filesystemLocation = JavaIndex.getLocationForElement(javaElement);

		TypeRef typeRef = TypeRef.create(index.getNd(),
				filesystemLocation.toString().toCharArray(),
				JavaNames.binaryNameToFieldDescriptor(binaryName.toCharArray()));

		char[] indexPath = (javaElement.getPath().toString() + "|" + binaryName + ".class").toCharArray(); //$NON-NLS-1$//$NON-NLS-2$
		return new IndexBinaryType(typeRef, indexPath);
	}

	/**
	 * Reads the given binary type. If the type can be found in the index with a fingerprint that exactly matches
	 * the file on disk, the type is read from the index. Otherwise the type is read from disk. Returns null if
	 * no such type exists.
	 * @throws ClassFormatException 
	 */
	public static IBinaryType readType(BinaryTypeDescriptor descriptor, 
			IProgressMonitor monitor) throws JavaModelException, ClassFormatException {
		
		if (JavaIndex.isEnabled()) {
			try {
				return readFromIndex(descriptor, monitor);
			} catch (NotInIndexException e) {
				// fall back to reading the zip file, below
			}
		}

		return rawReadType(descriptor, true);
	}

	/**
	 * Read the class file from disk, circumventing the index's cache. This should only be used by callers
	 * that need to read information from the class file which aren't present in the index (such as method bodies).
	 * 
	 * @return the newly-created ClassFileReader or null if the given class file does not exist.
	 * @throws ClassFormatException if the class file existed but was corrupt
	 * @throws JavaModelException if unable to read the class file due to a transient failure
	 */
	public static ClassFileReader rawReadType(BinaryTypeDescriptor descriptor, boolean fullyInitialize) throws JavaModelException, ClassFormatException {
		if (descriptor == null) {
			return null;
		}
		if (descriptor.isInJarFile()) {
			ZipFile zip = null;
			try {
				zip = JavaModelManager.getJavaModelManager().getZipFile(new Path(new String(descriptor.workspacePath)));
				char[] entryNameCharArray = CharArrayUtils.concat(
						JavaNames.fieldDescriptorToBinaryName(descriptor.fieldDescriptor), SuffixConstants.SUFFIX_class);
				String entryName = new String(entryNameCharArray);
				ZipEntry ze = zip.getEntry(entryName);
				if (ze != null) {
					byte contents[];
					try {
						contents = org.eclipse.jdt.internal.compiler.util.Util.getZipEntryByteContent(ze, zip);
					} catch (IOException ioe) {
						throw new JavaModelException(ioe, IJavaModelStatusConstants.IO_EXCEPTION);
					}
					return new ClassFileReader(contents, descriptor.indexPath, fullyInitialize);
				}
			} catch (CoreException e) {
				throw new JavaModelException(e);
			} finally {
				JavaModelManager.getJavaModelManager().closeZipFile(zip);
			}
		} else {
			IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(new String(descriptor.workspacePath)));
			byte[] contents = Util.getResourceContentsAsByteArray(file);
			return new ClassFileReader(contents, file.getFullPath().toString().toCharArray(), fullyInitialize);
		}
		return null;
	}

	/**
	 * Tries to read the given IBinaryType from the index. The return value is lightweight and may be cached
	 * with minimal memory cost. Returns an IBinaryType if the type was found in the index and the index
	 * was up-to-date. Throws a NotInIndexException if the index does not contain an up-to-date cache of the
	 * requested file. Returns null if the index contains an up-to-date cache of the requested file and it was
	 * able to determine that the requested class does not exist in that file.
	 */
	public static IBinaryType readFromIndex(BinaryTypeDescriptor descriptor, IProgressMonitor monitor) throws JavaModelException, NotInIndexException {
		char[] className = JavaNames.fieldDescriptorToSimpleName(descriptor.fieldDescriptor);

		// If the new index is enabled, check if we have this class file cached in the index already		
		char[] fieldDescriptor = descriptor.fieldDescriptor;

		if (!CharArrayUtils.equals(PACKAGE_INFO, className)) {
			JavaIndex index = JavaIndex.getIndex();
			Nd nd = index.getNd();

			// We don't currently cache package-info files in the index
			if (descriptor.location != null) {
				// Acquire a read lock on the index
				try (IReader lock = nd.acquireReadLock()) {
					try {
						TypeRef typeRef = TypeRef.create(nd, descriptor.location, fieldDescriptor);
						NdType type = typeRef.get();

						if (type == null) {
							// If we couldn't find the type in the index, determine whether the cause is
							// that the type is known not to exist or whether the resource just hasn't
							// been indexed yet

							NdResourceFile resourceFile = index.getResourceFile(descriptor.location);
							if (index.isUpToDate(resourceFile)) {
								return null;
							}
							throw new NotInIndexException();
						}
						NdResourceFile resourceFile = type.getResourceFile();
						if (index.isUpToDate(resourceFile)) {
							IndexBinaryType result = new IndexBinaryType(typeRef, descriptor.indexPath);

							// We already have the database lock open and have located the element, so we may as
							// well prefetch the inexpensive attributes.
							result.initSimpleAttributes();

							return result;
						}
						throw new NotInIndexException();
					} catch (CoreException e) {
						throw new JavaModelException(e);
					}
				} catch (IndexException e) {
					// Index corrupted. Rebuild it.
					index.rebuildIndex();
				}
			}
		}
		
		throw new NotInIndexException();
	}
}
