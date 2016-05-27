/*******************************************************************************
 * Copyright (c) 2015 Google, Inc and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Stefan Xenos (Google) - Initial implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.core.nd;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.internal.core.nd.db.ChunkCache;
import org.eclipse.jdt.internal.core.nd.db.Database;
import org.eclipse.jdt.internal.core.nd.db.IndexException;

/**
 * Network Database for storing semantic information.
 *
 * @since 3.12
 */
public class Nd {
	private static final int CANCELLATION_CHECK_INTERVAL = 500;
	private static final int BLOCKED_WRITE_LOCK_OUTPUT_INTERVAL = 30000;
	private static final int LONG_WRITE_LOCK_REPORT_THRESHOLD = 1000;
	private static final int LONG_READ_LOCK_WAIT_REPORT_THRESHOLD = 1000;
	static boolean sDEBUG_LOCKS= false;

	private final int currentVersion;
	private final int maxVersion;
	private final int minVersion;

	public static int version(int major, int minor) {
		return (major << 16) + minor;
	}

	/**
	 * Returns the version that shall be used when creating new databases.
	 */
	public int getDefaultVersion() {
		return this.currentVersion;
	}

	public boolean isSupportedVersion(int vers) {
		return vers >= this.minVersion && vers <= this.maxVersion;
	}

	public int getMinSupportedVersion() {
		return this.minVersion;
	}

	public int getMaxSupportedVersion() {
		return this.maxVersion;
	}

	public static String versionString(int version) {
		final int major= version >> 16;
		final int minor= version & 0xffff;
		return "" + major + '.' + minor; //$NON-NLS-1$
	}

	public interface Listener {
		void consume(ChangeEvent event);
	}

	public static class ChangeEvent {
		private final Set<LocalPath> filesModified;

		public ChangeEvent(Set<LocalPath> changes) {
			this.filesModified = changes;
		}

		public Set<LocalPath> getFilesModified() {
			return this.filesModified;
		}

		public boolean isTrivial() {
			return this.filesModified.isEmpty();
		}
	}

	// Local caches
	protected Database db;
	private File fPath;
	private final HashMap<Object, Object> fResultCache = new HashMap<>();
	/**
	 * Holds the set of files which have been changed since the last index event was fired
	 */
	private Set<LocalPath> changes = new HashSet<>();

	private final NdNodeTypeRegistry<NdNode> fNodeTypeRegistry;
	private HashMap<Long, Throwable> pendingDeletions = new HashMap<>();

	private IReader fReader = new IReader() {
		@Override
		public void close() {
			releaseReadLock();
		}
	};

	/**
	 * This long is incremented every time a change is written to the database. Can be used to determine if the database
	 * has changed.
	 */
	private long fWriteNumber;

	public Nd(File dbPath, NdNodeTypeRegistry<NdNode> nodeTypes, int minVersion, int maxVersion,
			int currentVersion) throws IndexException {
		this(dbPath, ChunkCache.getSharedInstance(), nodeTypes, minVersion, maxVersion, currentVersion);
	}

	public Nd(File dbPath, ChunkCache chunkCache, NdNodeTypeRegistry<NdNode> nodeTypes, int minVersion,
			int maxVersion, int currentVersion) throws IndexException {
		this.currentVersion = currentVersion;
		this.maxVersion = maxVersion;
		this.minVersion = minVersion;
		this.fNodeTypeRegistry = nodeTypes;
		loadDatabase(dbPath, chunkCache);
		if (sDEBUG_LOCKS) {
			this.fLockDebugging = new HashMap<>();
			System.out.println("Debugging database Locks"); //$NON-NLS-1$
		}
	}

	public long getWriteNumber() {
		return this.fWriteNumber;
	}

	public void scheduleDeletion(long addressOfNodeToDelete) {
		// Sometimes an object can be scheduled for deletion twice, if it is created and then discarded shortly
		// afterward during indexing. This may indicate an inefficiency in the indexer but is not necessarily
		// a bug.
		if (this.pendingDeletions.containsKey(addressOfNodeToDelete)) {
			Package.log("Database object queued for deletion twice", new RuntimeException()); //$NON-NLS-1$
			Package.log("Earlier deletion stack was this:", this.pendingDeletions.get(addressOfNodeToDelete)); //$NON-NLS-1$
			return;
		}
		this.pendingDeletions.put(addressOfNodeToDelete, new RuntimeException());
	}

	/**
	 * Synchronously processes all pending deletions
	 */
	public void processDeletions() {
		while (!this.pendingDeletions.isEmpty()) {
			long next = this.pendingDeletions.keySet().iterator().next();

			deleteIfUnreferenced(next);

			this.pendingDeletions.remove(next);
		}
	}

	/**
	 * Returns whether this {@link Nd} can never be written to. Writable subclasses should return false.
	 */
	protected boolean isPermanentlyReadOnly() {
		return false;
	}

	private void loadDatabase(File dbPath, ChunkCache cache) throws IndexException {
		this.fPath= dbPath;
		final boolean lockDB= this.db == null || this.lockCount != 0;

		clearCaches();
		this.db = new Database(this.fPath, cache, getDefaultVersion(), isPermanentlyReadOnly());

		this.db.setLocked(lockDB);
		if (!isSupportedVersion()) {
			Package.log("Index database is uses an unsupported version " + this.db.getVersion() //$NON-NLS-1$
				+ " Deleting and recreating.", null); //$NON-NLS-1$
			this.db.close();
			this.fPath.delete();
			this.db = new Database(this.fPath, cache, getDefaultVersion(), isPermanentlyReadOnly());
			this.db.setLocked(lockDB);
		}
		this.fWriteNumber = this.db.getLong(Database.WRITE_NUMBER_OFFSET);
		this.db.setLocked(this.lockCount != 0);
	}

	public Database getDB() {
		return this.db;
	}

	// Read-write lock rules. Readers don't conflict with other readers,
	// Writers conflict with readers, and everyone conflicts with writers.
	private final Object mutex = new Object();
	private int lockCount;
	private int waitingReaders;
	private long lastWriteAccess= 0;
	//private long lastReadAccess= 0;
	private long timeWriteLockAcquired;

	public IReader acquireReadLock() {
		try {
			long t = sDEBUG_LOCKS ? System.nanoTime() : 0;
			synchronized (this.mutex) {
				++this.waitingReaders;
				try {
					while (this.lockCount < 0)
						this.mutex.wait();
				} finally {
					--this.waitingReaders;
				}
				++this.lockCount;
				this.db.setLocked(true);

				if (sDEBUG_LOCKS) {
					t = (System.nanoTime() - t) / 1000000;
					if (t >= LONG_READ_LOCK_WAIT_REPORT_THRESHOLD) {
						System.out.println("Acquired index read lock after " + t + " ms wait."); //$NON-NLS-1$//$NON-NLS-2$
					}
					incReadLock(this.fLockDebugging);
				}
				return this.fReader;
			}
		} catch (InterruptedException e) {
			throw new OperationCanceledException();
		}
	}

	public void releaseReadLock() {
		synchronized (this.mutex) {
			assert this.lockCount > 0: "No lock to release"; //$NON-NLS-1$
			if (sDEBUG_LOCKS) {
				decReadLock(this.fLockDebugging);
			}

			//this.lastReadAccess= System.currentTimeMillis();
			if (this.lockCount > 0)
				--this.lockCount;
			this.mutex.notifyAll();
			this.db.setLocked(this.lockCount != 0);
		}
		// A lock release probably means that some AST is going away. The result cache has to be
		// cleared since it may contain objects belonging to the AST that is going away. A failure
		// to release an AST object would cause a memory leak since the whole AST would remain
		// pinned to memory.
		// TODO(sprigogin): It would be more efficient to replace the global result cache with
		// separate caches for each AST.
		//clearResultCache();
	}

	/**
	 * Acquire a write lock on this {@link Nd}. Blocks until any existing read/write locks are released.
	 * @throws OperationCanceledException
	 * @throws IllegalStateException if this {@link Nd} is not writable
	 */
	public void acquireWriteLock(IProgressMonitor monitor) {
		try {
			acquireWriteLock(0, monitor);
		} catch (InterruptedException e) {
			throw new OperationCanceledException();
		}
	}

	/**
	 * Acquire a write lock on this {@link Nd}, giving up the specified number of read locks first. Blocks
	 * until any existing read/write locks are released.
	 * @throws InterruptedException
	 * @throws IllegalStateException if this {@link Nd} is not writable
	 */
	public void acquireWriteLock(int giveupReadLocks, IProgressMonitor monitor) throws InterruptedException {
		assert !isPermanentlyReadOnly();
		synchronized (this.mutex) {
			if (sDEBUG_LOCKS) {
				incWriteLock(giveupReadLocks);
			}

			if (giveupReadLocks > 0) {
				// give up on read locks
				assert this.lockCount >= giveupReadLocks: "Not enough locks to release"; //$NON-NLS-1$
				if (this.lockCount < giveupReadLocks) {
					giveupReadLocks= this.lockCount;
				}
			} else {
				giveupReadLocks= 0;
			}

			// Let the readers go first
			long start= sDEBUG_LOCKS ? System.currentTimeMillis() : 0;
			while (this.lockCount > giveupReadLocks || this.waitingReaders > 0) {
				this.mutex.wait(CANCELLATION_CHECK_INTERVAL);
				if (monitor != null && monitor.isCanceled()) {
					throw new OperationCanceledException();
				}
				if (sDEBUG_LOCKS) {
					start = reportBlockedWriteLock(start, giveupReadLocks);
				}
			}
			this.lockCount= -1;
			if (sDEBUG_LOCKS)
				this.timeWriteLockAcquired = System.currentTimeMillis();
			this.db.setExclusiveLock();
		}
	}

	/**
	 * Should be called by the indexer to indicate a source file that has been
	 * fully indexed.
	 */
	public final void markPathAsModified(LocalPath path) {
		this.db.assertLocked();
		this.changes.add(path);
	}

	public final void releaseWriteLock() {
		releaseWriteLock(0, true);
	}

	@SuppressWarnings("nls")
	public void releaseWriteLock(int establishReadLocks, boolean flush) {
		// When all locks are released we can clear the result cache.
		if (establishReadLocks == 0) {
			processDeletions();
			this.db.putLong(Database.WRITE_NUMBER_OFFSET, ++this.fWriteNumber);
			clearResultCache();
		}
		try {
			this.db.giveUpExclusiveLock(flush);
		} catch (IndexException e) {
			Package.log(e);
		}
		assert this.lockCount == -1;
		if (!this.changes.isEmpty()) {
			this.lastWriteAccess= System.currentTimeMillis();
			this.changes = new HashSet<>();
		}
		synchronized (this.mutex) {
			if (sDEBUG_LOCKS) {
				long timeHeld = this.lastWriteAccess - this.timeWriteLockAcquired;
				if (timeHeld >= LONG_WRITE_LOCK_REPORT_THRESHOLD) {
					System.out.println("Index write lock held for " + timeHeld + " ms");
				}
				decWriteLock(establishReadLocks);
			}

			if (this.lockCount < 0)
				this.lockCount= establishReadLocks;
			this.mutex.notifyAll();
			this.db.setLocked(this.lockCount != 0);
		}
	}

	public boolean hasWaitingReaders() {
		synchronized (this.mutex) {
			return this.waitingReaders > 0;
		}
	}

	public long getLastWriteAccess() {
		return this.lastWriteAccess;
	}

	public boolean isSupportedVersion() throws IndexException {
		final int version = this.db.getVersion();
		return version >= this.minVersion && version <= this.maxVersion;
	}

	public void close() throws IndexException {
		this.db.close();
		clearCaches();
	}

	private void clearCaches() {
//		fileIndex= null;
//		tagIndex = null;
//		indexOfDefectiveFiles= null;
//		indexOfFiledWithUnresolvedIncludes= null;
//		fLinkageIDCache.clear();
		clearResultCache();
	}

	public void clearResultCache() {
		synchronized (this.fResultCache) {
			this.fResultCache.clear();
		}
	}

	public Object getCachedResult(Object key) {
		synchronized (this.fResultCache) {
			return this.fResultCache.get(key);
		}
	}

	public void putCachedResult(Object key, Object result) {
		putCachedResult(key, result, true);
	}

	public Object putCachedResult(Object key, Object result, boolean replace) {
		synchronized (this.fResultCache) {
			Object old= this.fResultCache.put(key, result);
			if (old != null && !replace) {
				this.fResultCache.put(key, old);
				return old;
			}
			return result;
		}
	}

	public void removeCachedResult(Object key) {
		synchronized (this.fResultCache) {
			this.fResultCache.remove(key);
		}
	}

	// For debugging lock issues
	static class DebugLockInfo {
		int fReadLocks;
		int fWriteLocks;
		List<StackTraceElement[]> fTraces= new ArrayList<>();

		public int addTrace() {
			this.fTraces.add(Thread.currentThread().getStackTrace());
			return this.fTraces.size();
		}

		@SuppressWarnings("nls")
		public void write(String threadName) {
			System.out.println("Thread: '" + threadName + "': " + this.fReadLocks + " readlocks, " + this.fWriteLocks + " writelocks");
			for (StackTraceElement[] trace : this.fTraces) {
				System.out.println("  Stacktrace:");
				for (StackTraceElement ste : trace) {
					System.out.println("    " + ste);
				}
			}
		}

		public void inc(DebugLockInfo val) {
			this.fReadLocks+= val.fReadLocks;
			this.fWriteLocks+= val.fWriteLocks;
			this.fTraces.addAll(val.fTraces);
		}
	}

	// For debugging lock issues
	private Map<Thread, DebugLockInfo> fLockDebugging;

	// For debugging lock issues
	private static DebugLockInfo getLockInfo(Map<Thread, DebugLockInfo> lockDebugging) {
		assert sDEBUG_LOCKS;

		Thread key = Thread.currentThread();
		DebugLockInfo result= lockDebugging.get(key);
		if (result == null) {
			result= new DebugLockInfo();
			lockDebugging.put(key, result);
		}
		return result;
	}

	// For debugging lock issues
	static void incReadLock(Map<Thread, DebugLockInfo> lockDebugging) {
		DebugLockInfo info = getLockInfo(lockDebugging);
		info.fReadLocks++;
		if (info.addTrace() > 10) {
			outputReadLocks(lockDebugging);
		}
	}

	// For debugging lock issues
	@SuppressWarnings("nls")
	static void decReadLock(Map<Thread, DebugLockInfo> lockDebugging) throws AssertionError {
		DebugLockInfo info = getLockInfo(lockDebugging);
		if (info.fReadLocks <= 0) {
			outputReadLocks(lockDebugging);
			throw new AssertionError("Superfluous releaseReadLock");
		}
		if (info.fWriteLocks != 0) {
			outputReadLocks(lockDebugging);
			throw new AssertionError("Releasing readlock while holding write lock");
		}
		if (--info.fReadLocks == 0) {
			lockDebugging.remove(Thread.currentThread());
		} else {
			info.addTrace();
		}
	}

	// For debugging lock issues
	@SuppressWarnings("nls")
	private void incWriteLock(int giveupReadLocks) throws AssertionError {
		DebugLockInfo info = getLockInfo(this.fLockDebugging);
		if (info.fReadLocks != giveupReadLocks) {
			outputReadLocks(this.fLockDebugging);
			throw new AssertionError("write lock with " + giveupReadLocks + " readlocks, expected " + info.fReadLocks);
		}
		if (info.fWriteLocks != 0)
			throw new AssertionError("Duplicate write lock");
		info.fWriteLocks++;
	}

	// For debugging lock issues
	private void decWriteLock(int establishReadLocks) throws AssertionError {
		DebugLockInfo info = getLockInfo(this.fLockDebugging);
		if (info.fReadLocks != establishReadLocks)
			throw new AssertionError("release write lock with " + establishReadLocks + " readlocks, expected " + info.fReadLocks); //$NON-NLS-1$ //$NON-NLS-2$
		if (info.fWriteLocks != 1)
			throw new AssertionError("Wrong release write lock"); //$NON-NLS-1$
		info.fWriteLocks= 0;
		if (info.fReadLocks == 0) {
			this.fLockDebugging.remove(Thread.currentThread());
		}
	}

	// For debugging lock issues
	@SuppressWarnings("nls")
	private long reportBlockedWriteLock(long start, int giveupReadLocks) {
		long now= System.currentTimeMillis();
		if (now >= start + BLOCKED_WRITE_LOCK_OUTPUT_INTERVAL) {
			System.out.println();
			System.out.println("Blocked writeLock");
			System.out.println("  lockcount= " + this.lockCount + ", giveupReadLocks=" + giveupReadLocks + ", waitingReaders=" + this.waitingReaders);
			outputReadLocks(this.fLockDebugging);
			start= now;
		}
		return start;
	}

	// For debugging lock issues
	@SuppressWarnings("nls")
	private static void outputReadLocks(Map<Thread, DebugLockInfo> lockDebugging) {
		System.out.println("---------------------  Lock Debugging -------------------------");
		for (Thread th: lockDebugging.keySet()) {
			DebugLockInfo info = lockDebugging.get(th);
			info.write(th.getName());
		}
		System.out.println("---------------------------------------------------------------");
	}

	// For debugging lock issues
	public void adjustThreadForReadLock(Map<Thread, DebugLockInfo> lockDebugging) {
		for (Thread th : lockDebugging.keySet()) {
			DebugLockInfo val= lockDebugging.get(th);
			if (val.fReadLocks > 0) {
				DebugLockInfo myval= this.fLockDebugging.get(th);
				if (myval == null) {
					myval= new DebugLockInfo();
					this.fLockDebugging.put(th, myval);
				}
				myval.inc(val);
				for (int i = 0; i < val.fReadLocks; i++) {
					decReadLock(this.fLockDebugging);
				}
			}
		}
	}

    public NdNode getNode(long address, short nodeType) throws IndexException {
    	return this.fNodeTypeRegistry.createNode(this, address, nodeType);
    }

    public <T extends NdNode> ITypeFactory<T> getTypeFactory(short nodeType) {
    	return this.fNodeTypeRegistry.getTypeFactory(nodeType);
    }

	/**
	 * Returns the type ID for the given class
	 */
	public short getNodeType(Class<? extends NdNode> toQuery) {
		return this.fNodeTypeRegistry.getTypeForClass(toQuery);
	}

	private void deleteIfUnreferenced(long address) {
		if (address == 0) {
			return;
		}
		short nodeType = NdNode.NODE_TYPE.get(this, address);

		// Look up the type
		ITypeFactory<? extends NdNode> factory1 = getTypeFactory(nodeType);

		if (factory1.isReadyForDeletion(this, address)) {
			// Call its destructor
			factory1.destruct(this, address);

			// Free up its memory
			getDB().free(address);
		}
	}

	public void delete(long address) {
		if (address == 0) {
			return;
		}
		short nodeType = NdNode.NODE_TYPE.get(this, address);

		// Look up the type
		ITypeFactory<? extends NdNode> factory1 = getTypeFactory(nodeType);

		// Call its destructor
		factory1.destruct(this, address);

		// Free up its memory
		getDB().free(address);
	}
}
