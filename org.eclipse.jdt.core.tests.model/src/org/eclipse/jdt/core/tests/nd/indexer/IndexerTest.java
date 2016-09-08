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
package org.eclipse.jdt.core.tests.nd.indexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IParent;
import org.eclipse.jdt.core.tests.model.AbstractJavaModelTests;
import org.eclipse.jdt.internal.compiler.env.IBinaryType;
import org.eclipse.jdt.internal.core.PackageFragment;
import org.eclipse.jdt.internal.core.nd.IReader;
import org.eclipse.jdt.internal.core.nd.db.Database;
import org.eclipse.jdt.internal.core.nd.indexer.ClassFileToIndexConverter;
import org.eclipse.jdt.internal.core.nd.indexer.IndexTester;
import org.eclipse.jdt.internal.core.nd.indexer.Indexer;
import org.eclipse.jdt.internal.core.nd.java.JavaIndex;
import org.eclipse.jdt.internal.core.nd.java.JavaNames;
import org.eclipse.jdt.internal.core.nd.java.NdType;
import org.eclipse.jdt.internal.core.nd.java.NdTypeId;
import org.eclipse.jdt.internal.core.nd.java.model.BinaryTypeFactory;
import org.eclipse.jdt.internal.core.nd.java.model.IndexBinaryType;
import org.eclipse.jdt.internal.core.util.Util;

import junit.framework.Test;

/**
 * Tests for the {@link Database} class.
 */
public class IndexerTest extends AbstractJavaModelTests {

	public IndexerTest(String name) {
		super(name);
	}

	private static final String PROJECT_NAME = "IndexerTest";
	private static JavaIndex index;

	@Override
	protected void setUp() throws Exception {
		String testName = getName();
		index = JavaIndexTestUtil.createTempIndex(testName);
		super.setUp();
	}

	@Override
	protected void tearDown() throws Exception {
		deleteProject(PROJECT_NAME);
		index.getNd().getPath().delete();
		index = null;
		super.tearDown();
	}

	public static Test suite() {
		return buildModelTestSuite(IndexerTest.class);
	}

	public void testSubclassesOfGenericTypeCanBeFound() throws Exception {
		createJavaProject(PROJECT_NAME, new String[] {"src"}, new String[] {"JCL18_FULL"}, "bin", "1.8", true);
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		Indexer indexer = new Indexer(index.getNd(), root);

		indexer.rescan(SubMonitor.convert(null));

		try (IReader reader = IndexerTest.index.getNd().acquireReadLock()) {
			NdTypeId javaUtilList = IndexerTest.index.findType("Ljava/util/List;".toCharArray());
			NdTypeId javaUtilArrayList = IndexerTest.index.findType("Ljava/util/ArrayList;".toCharArray());

			boolean found = false;
			List<NdType> subtypes = javaUtilList.getSubTypes();
			for (NdType next : subtypes) {
				if (Objects.equals(next.getTypeId(), javaUtilArrayList)) {
					found = true;
				}
			}

			assertTrue("ArrayList was found as a subtype of List", found);
		}
	}

	private void collectAllClassFiles(List<? super IClassFile> result, IParent nextRoot) throws CoreException {
		for (IJavaElement child : nextRoot.getChildren()) {
			int type = child.getElementType();

			if (type == IJavaElement.CLASS_FILE) {
				result.add((IClassFile)child);
			} else if (child instanceof IParent) {
				IParent parent = (IParent) child;

				collectAllClassFiles(result, parent);
			}
		}
	}

	public void testReadingAllClassesInIndexAreEquivalentToOriginalJarFiles() throws Exception {
		IJavaProject javaProject = createJavaProject(PROJECT_NAME, new String[] {"src"}, new String[] {"JCL18_FULL"}, "bin", "1.8", true);
		addClassFolder(javaProject, "lib", new String[] {
				"p/Outer.java",
				"import java.lang.annotation.*;\n" +
				"\n" +
				"@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) @interface A {}\n" +
				"@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) @interface M {}\n" +
				"@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER) @interface P {}\n" +
				"\n" +
				"class Outer {\n" +
				"    class Middle1 {\n" +
				"        class Inner {}\n" +
				"    }\n" +
				"    static class Middle2 {\n" +
				"        class Inner {}\n" +
				"        static class Middle3 {\n" +
				"            class Inner2{};\n" +
				"        }\n" +
				"    }\n" +
				"    Middle1.@A Inner e1;\n" +
				"    Middle2.@A Inner e2;\n" +
				"    Middle2.Middle3.@A Inner2 e3;\n" +
				"    @M void foo(@P Middle2.Middle3.@A Inner2 e3) {};\n" +
				"    class Middle4 extends @A Middle1 {}\n" +
				"}\n",
			}, "1.8");

		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		Indexer indexer = new Indexer(index.getNd(), root);

		indexer.rescan(SubMonitor.convert(null));

		boolean foundAtLeastOneClass = false;
		SubMonitor subMonitor = SubMonitor.convert(null);
		JavaIndex localIndex = IndexerTest.index;
		try (IReader reader = localIndex.getNd().acquireReadLock()) {
			IPackageFragmentRoot[] roots = javaProject.getAllPackageFragmentRoots();
			subMonitor.setWorkRemaining(roots.length);
			for (IPackageFragmentRoot next : roots) {
				SubMonitor iterationMon = subMonitor.split(1);
				if (next.getKind() == IPackageFragmentRoot.K_BINARY) {
					List<IClassFile> result = new ArrayList<>();
					collectAllClassFiles(result, next);
					for (IClassFile nextClass : result) {
						IBinaryType originalBinaryType = ClassFileToIndexConverter.getTypeFromClassFile(nextClass, iterationMon);
						PackageFragment pkg = (PackageFragment) nextClass.getParent();
						String classFilePath = Util.concatWith(pkg.names, nextClass.getElementName(), '/');

						IndexBinaryType indexedBinaryType = (IndexBinaryType)BinaryTypeFactory.create(localIndex, nextClass,
								JavaNames.classFilePathToBinaryName(classFilePath));

						if (!indexedBinaryType.exists()) {
							throw new IllegalStateException("Unable to find class in index " + classFilePath);
						}
						IndexTester.testType(originalBinaryType, indexedBinaryType);
						foundAtLeastOneClass = true;
					}
				}
			}
		}
		assertTrue("No classes found in the index", foundAtLeastOneClass);
	}
}
