package org.eclipse.jdt.internal.core;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IRegion;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class LargeRegion implements IRegion {

	private static final class Node {
		private static Map<IJavaElement, Node> children = Collections.emptyMap();

		public Node() {
		}

		public void clearChildren() {
			children = Collections.emptyMap();
		}

		public Node createChildFor(IJavaElement element) {
			if (children.isEmpty()) {
				children = new HashMap<>();
			}
			
			Node child = children.get(element);

			if (child == null) {
				child = new Node();
				children.put(element, child);
			}

			return child;
		}

		public Node findChildFor(IJavaElement element) {
			return children.get(element);
		}

		public int countLeafNodes() {
			if (isEmpty()) {
				return 1;
			}
			
			int result = 0;
			for (Node next: children.values()) {
				result += next.countLeafNodes();
			}
			return result;
		}

		boolean isEmpty() {
			return children.isEmpty();
		}

		public int gatherLeaves(IJavaElement[] result, int i) {
			for (Map.Entry<IJavaElement, Node> next : children.entrySet()) {
				Node nextNode = next.getValue();
				if (nextNode.isEmpty()) {
					result[i++] = next.getKey();
				} else {
					i = nextNode.gatherLeaves(result, i);
				}
			}
			return i;
		}
	}
	private Node root = new Node();

	@Override
	public void add(IJavaElement element) {
		if (contains(element)) {
			return;
		}
		Node node = createNodeFor(element);
		node.clearChildren();
	}

	private Node createNodeFor(IJavaElement element) {
		if (element == null) {
			return this.root;
		}

		Node parentNode = createNodeFor(element.getParent());

		return parentNode.createChildFor(element);
	}

	@Override
	public boolean contains(IJavaElement element) {
		Node existingNode = findNodeFor(element);
		
		if (existingNode == null || existingNode == root) {
			return false;
		}
		return existingNode.isEmpty();
	}

	private Node findNodeFor(IJavaElement element) {
		if (element == null) {
			return this.root;
		}

		Node parentNode = findNodeFor(element.getParent());

		if (parentNode == null) {
			return null;
		}

		return parentNode.findChildFor(element);
	}

	@Override
	public IJavaElement[] getElements() {
		int leaves = countLeafNodes();

		IJavaElement[] result = new IJavaElement[leaves];
		int insertions = this.root.gatherLeaves(result, 0);

		assert insertions == leaves;

		return result;
	}

	private int countLeafNodes() {
		if (this.root.isEmpty()) {
			return 0;
		}
		return this.root.countLeafNodes();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.IRegion#remove(org.eclipse.jdt.core.IJavaElement)
	 */
	@Override
	public boolean remove(IJavaElement element) {
		// TODO(${user}): Auto-generated method stub
		return false;
	}

}
