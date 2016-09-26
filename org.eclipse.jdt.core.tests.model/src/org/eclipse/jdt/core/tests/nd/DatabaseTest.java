/*******************************************************************************
 * Copyright (c) 2005, 2016 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     QNX Software Systems - initial API and implementation
 *     Andrew Ferguson (Symbian)
 *     Markus Schorn (Wind River Systems)
 *******************************************************************************/
package org.eclipse.jdt.core.tests.nd;

import java.io.File;
import java.util.Random;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.tests.nd.util.BaseTestCase;
import org.eclipse.jdt.internal.core.nd.Nd;
import org.eclipse.jdt.internal.core.nd.NdNode;
import org.eclipse.jdt.internal.core.nd.NdNodeTypeRegistry;
import org.eclipse.jdt.internal.core.nd.db.BTree;
import org.eclipse.jdt.internal.core.nd.db.ChunkCache;
import org.eclipse.jdt.internal.core.nd.db.Database;
import org.eclipse.jdt.internal.core.nd.db.IBTreeComparator;
import org.eclipse.jdt.internal.core.nd.db.IBTreeVisitor;
import org.eclipse.jdt.internal.core.nd.db.IString;
import org.eclipse.jdt.internal.core.nd.db.IndexException;
import org.eclipse.jdt.internal.core.nd.db.ShortString;

import junit.framework.Test;

/**
 * Tests for the {@link Database} class.
 */
public class DatabaseTest extends BaseTestCase {
	// This constant can be used to run the test with very large databases.
	// Try, for example, setting it to Integer.MAX_VALUE * 7L;
	private static final long TEST_OFFSET = 0;
	private Nd nd;
	protected Database db;
	private static final int CURRENT_VERSION = 10;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		String testName = getName();
		NdNodeTypeRegistry<NdNode> registry = new NdNodeTypeRegistry<>();
		nd = new Nd(DatabaseTestUtil.getTempDbName(testName), new ChunkCache(), registry,
				0, 100, CURRENT_VERSION);
		db = nd.getDB();
		db.setExclusiveLock();

		// Allocate all database chunks up to TEST_OFFSET.
		int count = 0;
		for (long offset = 0; offset < TEST_OFFSET;) {
			offset = db.malloc(Database.MAX_MALLOC_SIZE, Database.POOL_MISC);
			if (++count >= 1000) {
				db.flush();
				count = 0;
			}
		}
		db.flush();
	}

	public static Test suite() {
		return BaseTestCase.suite(DatabaseTest.class);
	}

	@Override
	protected void tearDown() throws Exception {
		db.close();
		if (!db.getLocation().delete()) {
			db.getLocation().deleteOnExit();
		}
		db= null;
	}

	public void testBlockSizeAndFirstBlock() throws Exception {
		assertEquals(CURRENT_VERSION, db.getVersion());

		final int realsize = 42;
		final int deltas = (realsize + Database.BLOCK_HEADER_SIZE + Database.BLOCK_SIZE_DELTA - 1) / Database.BLOCK_SIZE_DELTA;
		final int blocksize = deltas * Database.BLOCK_SIZE_DELTA;
		final int freeDeltas= Database.CHUNK_SIZE / Database.BLOCK_SIZE_DELTA - deltas;

		long mem = db.malloc(realsize, Database.POOL_MISC);
		assertEquals(-blocksize, db.getShort(mem - Database.BLOCK_HEADER_SIZE));
		db.free(mem, Database.POOL_MISC);
		assertEquals(blocksize, db.getShort(mem - Database.BLOCK_HEADER_SIZE));
		assertEquals(mem, db.getRecPtr((deltas - Database.MIN_BLOCK_DELTAS +1 ) * Database.INT_SIZE));
		assertEquals(mem + blocksize, db.getRecPtr((freeDeltas - Database.MIN_BLOCK_DELTAS + 1) * Database.INT_SIZE));
	}

	public void testBug192437() throws Exception {
		File tmp= File.createTempFile("readOnlyEmpty", ".db");
		try {
			tmp.setReadOnly();

			/* check opening a readonly file for rw access fails */
			try {
				new Database(tmp, ChunkCache.getSharedInstance(), 0, false);
				fail("A readonly file should not be openable with write-access");
			} catch (IndexException e) {
				// we expect to get a failure here
			}

			/* check opening a readonly file for read access does not fail */
			try {
				new Database(tmp, ChunkCache.getSharedInstance(), 0, true);
			} catch (IndexException e) {
				fail("A readonly file should be readable by a permanently readonly database " + e);
			}
		} finally {
			tmp.delete(); // this may be pointless on some platforms
		}
	}

	public void testFreeBlockLinking() throws Exception {
		final int realsize = 42;
		final int deltas = (realsize + Database.BLOCK_HEADER_SIZE + Database.BLOCK_SIZE_DELTA - 1) / Database.BLOCK_SIZE_DELTA;
		final int blocksize = deltas * Database.BLOCK_SIZE_DELTA;
		final int freeDeltas= Database.MIN_BLOCK_DELTAS - deltas;

		long mem1 = db.malloc(realsize, Database.POOL_MISC);
		long mem2 = db.malloc(realsize, Database.POOL_MISC);
		db.free(mem1, Database.POOL_MISC);
		db.free(mem2, Database.POOL_MISC);
		assertEquals(mem2, db.getRecPtr((deltas - Database.MIN_BLOCK_DELTAS + 1) * Database.INT_SIZE));
		assertEquals(0, db.getRecPtr(mem2));
		assertEquals(mem1, db.getRecPtr(mem2 + Database.INT_SIZE));
		assertEquals(mem2, db.getRecPtr(mem1));
		assertEquals(0, db.getRecPtr(mem1 + Database.INT_SIZE));
	}

	public void testSimpleAllocationLifecycle() throws Exception {
		long mem1 = db.malloc(42, Database.POOL_MISC);
		db.free(mem1, Database.POOL_MISC);
		long mem2 = db.malloc(42, Database.POOL_MISC);
		assertEquals(mem2, mem1);
	}

	private static class FindVisitor implements IBTreeVisitor {
		private Database db;
		private String key;
		private long record;

		public FindVisitor(Database db, String key) {
			this.db = db;
			this.key = key;
		}

		@Override
		public int compare(long record) {
			return db.getString(db.getRecPtr(record + 4)).compare(key, true);
		}

		@Override
		public boolean visit(long record) {
			this.record = record;
			return false;
		}

		public long getRecord() {
			return record;
		}
	}

	public void testStringsInBTree() throws Exception {
		String[] names = {
				"ARLENE",
				"BRET",
				"CINDY",
				"DENNIS",
				"EMILY",
				"FRANKLIN",
				"GERT",
				"HARVEY",
				"IRENE",
				"JOSE",
				"KATRINA",
				"LEE",
				"MARIA",
				"NATE",
				"OPHELIA",
				"PHILIPPE",
				"RITA",
				"STAN",
				"TAMMY",
				"VINCE",
				"WILMA",
				"ALPHA",
				"BETA"
		};

		IBTreeComparator comparator = new IBTreeComparator() {
			@Override
			public int compare(Nd nd, long record1, long record2) {
				IString string1 = db.getString(db.getRecPtr(record1 + 4));
				IString string2 = db.getString(db.getRecPtr(record2 + 4));
				return string1.compare(string2, true);
			}
		};

		BTree btree = new BTree(nd, Database.DATA_AREA_OFFSET, comparator);
		for (int i = 0; i < names.length; ++i) {
			String name = names[i];
			long record = db.malloc(8, Database.POOL_MISC);
			db.putInt(record + 0, i);
			IString string = db.newString(name);
			db.putRecPtr(record + 4, string.getRecord());
			btree.insert(record);
		}

		for (int i = 0; i < names.length; ++i) {
			String name = names[i];
			FindVisitor finder = new FindVisitor(db, name);
			btree.accept(finder);
			long record = finder.getRecord();
			assertTrue(record != 0);
			assertEquals(i, db.getInt(record));
			IString rname = db.getString(db.getRecPtr(record + 4));
			assertTrue(rname.equals(name));
		}
	}

	private final int GT = 1, LT = -1, EQ = 0;

	public void testShortStringComparison() throws CoreException {
		Random r= new Random(90210);

		assertCMP("",  EQ, "", true);
		assertCMP("",  EQ, "", false);

		doTrials(1000, 1, ShortString.MAX_BYTE_LENGTH / 2, r, true);
		doTrials(1000, 1, ShortString.MAX_BYTE_LENGTH / 2, r, false);
		doTrials(1000, 1, ShortString.MAX_BYTE_LENGTH, r, true);
		doTrials(1000, 1, ShortString.MAX_BYTE_LENGTH, r, false);

		assertCMP("a",  LT, "b", true);
		assertCMP("aa", LT, "ab", true);
		assertCMP("a",  EQ, "a", true);

		assertCMP("a",  GT, "A", true);
		assertCMP("aa", GT, "aA", true);
		assertCMP("a",  GT, "B", true);

		assertCMP("a",  EQ, "a", false);
		assertCMP("a",  EQ, "A", false);
	}

	public void testLongStringComparison() throws CoreException {
		Random r= new Random(314159265);
		doTrials(100, ShortString.MAX_BYTE_LENGTH + 1, ShortString.MAX_BYTE_LENGTH * 2, r, true);
		doTrials(100, ShortString.MAX_BYTE_LENGTH + 1, ShortString.MAX_BYTE_LENGTH * 2, r, false);
	}

	private void doTrials(int n, int min, int max, Random r, boolean caseSensitive) throws CoreException {
		long start = System.currentTimeMillis();
		for (int i= 0; i < n; i++) {
			String a = randomString(min, max, r);
			String b = randomString(min, max, r);
			int expected = caseSensitive ? a.compareTo(b) : a.compareToIgnoreCase(b);
			assertCMP(a, expected, b, caseSensitive);
		}
//		System.out.print("Trials: " + n + " Max length: " + max + " ignoreCase: " + !caseSensitive);
//		System.out.println(" Time: " + (System.currentTimeMillis() - start));
	}

	private String randomString(int min, int max, Random r) {
		int len = min + r.nextInt(max - min);
		return randomString(len, r);
	}

	private String randomString(int len, Random r) {
		StringBuilder result = new StringBuilder(len);
		for (int i= 0; i < len; i++) {
			result.append(randomChar(r));
		}
		return result.toString();
	}

	private char randomChar(Random r) {
		// We only match String.compareToIgnoreCase behavior within this limited range.
		return (char) (32 + r.nextInt(40));
	}

	private void assertCMP(String a, int expected, String b, boolean caseSensitive) throws CoreException {
		char[] acs = a.toCharArray();
		char[] bcs = b.toCharArray();
		IString aiss = db.newString(a);
		IString biss = db.newString(b);
		IString aisc = db.newString(acs);
		IString bisc = db.newString(bcs);

		assertEquals(a.hashCode(), aiss.hashCode());
		assertEquals(a.hashCode(), aisc.hashCode());
		assertEquals(b.hashCode(), biss.hashCode());
		assertEquals(b.hashCode(), bisc.hashCode());

		assertEquals(aiss, a);
		assertEquals(aisc, a);
		assertEquals(biss, b);
		assertEquals(bisc, b);

		assertSignEquals(expected, aiss.compare(bcs, caseSensitive));
		assertSignEquals(expected, aiss.compare(biss, caseSensitive));
		assertSignEquals(expected, aiss.compare(bisc, caseSensitive));
		assertSignEquals(expected, aiss.compare(b, caseSensitive));
		assertSignEquals(expected, aiss.comparePrefix(bcs, caseSensitive));

		assertSignEquals(expected, -biss.compare(acs, caseSensitive));
		assertSignEquals(expected, -biss.compare(aiss, caseSensitive));
		assertSignEquals(expected, -biss.compare(aisc, caseSensitive));
		assertSignEquals(expected, -biss.compare(a, caseSensitive));
		assertSignEquals(expected, -biss.comparePrefix(acs, caseSensitive));

		if (!caseSensitive && expected != 0) {
			assertSignEquals(expected, aiss.compareCompatibleWithIgnoreCase(bcs));
			assertSignEquals(expected, aiss.compareCompatibleWithIgnoreCase(biss));
			assertSignEquals(expected, aiss.compareCompatibleWithIgnoreCase(bisc));

			assertSignEquals(expected, -biss.compareCompatibleWithIgnoreCase(acs));
			assertSignEquals(expected, -biss.compareCompatibleWithIgnoreCase(aiss));
			assertSignEquals(expected, -biss.compareCompatibleWithIgnoreCase(aisc));
		}
	}

	private void assertSignEquals(int a, int b) {
		a= a < 0 ? -1 : (a > 0 ? 1 : 0);
		b= b < 0 ? -1 : (b > 0 ? 1 : 0);
		assertEquals(a, b);
	}
}
