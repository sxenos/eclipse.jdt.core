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
package org.eclipse.jdt.internal.core.index;

import org.eclipse.jdt.core.search.*;
import org.eclipse.jdt.internal.core.util.*;
import org.eclipse.jdt.internal.compiler.util.HashtableOfObject;

public class MemoryIndex {

//public int NUM_CHANGES = 10; // number of separate document changes... used to decide when to merge

SimpleLookupTable docsToReferences; // document paths -> HashtableOfObject(category names -> set of words)

MemoryIndex() {
	this.docsToReferences = new SimpleLookupTable();
}
void addDocumentNames(String substring, SimpleSet results) {
	// assumed the disk index already skipped over documents which have been added/changed/deleted
	Object[] paths = this.docsToReferences.keyTable;
	Object[] categoryTables = this.docsToReferences.valueTable;
	if (substring == null) { // add all new/changed documents
		for (int i = 0, l = categoryTables.length; i < l; i++)
			if (categoryTables[i] != null)
				results.add(paths[i]);
	} else {
		for (int i = 0, l = categoryTables.length; i < l; i++)
			if (categoryTables[i] != null && ((String) paths[i]).startsWith(substring, 0))
				results.add(paths[i]);
	}
}
void addIndexEntry(char[] category, char[] key, SearchDocument document) {
	// assumed a document was removed before its reindexed
	String documentName = document.getPath();
	HashtableOfObject categoryTable = (HashtableOfObject) this.docsToReferences.get(documentName);
	if (categoryTable == null)
		this.docsToReferences.put(documentName, categoryTable = new HashtableOfObject(3));

	SimpleWordSet existingWords = (SimpleWordSet) categoryTable.get(category);
	if (existingWords == null)
		categoryTable.put(category, existingWords = new SimpleWordSet(3));

	existingWords.add(key);
}
void addQueryResults(char[][] categories, char[] key, int matchRule, HashtableOfObject results) {
	// assumed the disk index already skipped over documents which have been added/changed/deleted
	// results maps a word -> EntryResult
	Object[] paths = this.docsToReferences.keyTable;
	Object[] categoryTables = this.docsToReferences.valueTable;
	if (matchRule == (SearchPattern.R_EXACT_MATCH + SearchPattern.R_CASE_SENSITIVE) && key != null) {
		nextPath : for (int i = 0, l = categoryTables.length; i < l; i++) {
			HashtableOfObject categoryToWords = (HashtableOfObject) categoryTables[i];
			if (categoryToWords != null) {
				for (int j = 0, m = categories.length; j < m; j++) {
					SimpleWordSet wordSet = (SimpleWordSet) categoryToWords.get(categories[j]);
					if (wordSet != null && wordSet.includes(key)) {
						EntryResult result = (EntryResult) results.get(key);
						if (result == null)
							results.put(key, result = new EntryResult(key, null));
						result.addDocumentName((String) paths[i]);
						continue nextPath;
					}
				}
			}
		}
	} else {
		for (int i = 0, l = categoryTables.length; i < l; i++) {
			HashtableOfObject categoryToWords = (HashtableOfObject) categoryTables[i];
			if (categoryToWords != null) {
				for (int j = 0, m = categories.length; j < m; j++) {
					SimpleWordSet wordSet = (SimpleWordSet) categoryToWords.get(categories[j]);
					if (wordSet != null) {
						char[][] words = wordSet.words;
						for (int k = 0, n = words.length; k < n; k++) {
							char[] word = words[k];
							if (word != null && Index.isMatch(key, word, matchRule)) {
								EntryResult result = (EntryResult) results.get(word);
								if (result == null)
									results.put(word, result = new EntryResult(word, null));
								result.addDocumentName((String) paths[i]);
							}
						}
					}
				}
			}
		}
	}
}
boolean hasChanged() {
	return this.docsToReferences.elementSize > 0;
}
void remove(String documentName) {
	this.docsToReferences.put(documentName, null);
}
//boolean shouldMerge() {
//	// call before query(char[][] categories, char[] key, int matchRule) since it can be slow if there are numerous changes
//	return this.docsToReferences.elementSize >= NUM_CHANGES;
//}
}