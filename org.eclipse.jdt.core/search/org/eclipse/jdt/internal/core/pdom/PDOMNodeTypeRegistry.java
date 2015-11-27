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
package org.eclipse.jdt.internal.core.pdom;

import org.eclipse.jdt.internal.core.pdom.db.IndexException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Maps integer constants onto factories for PDOMNode objects
 * @since 3.12
 */
public class PDOMNodeTypeRegistry<R> {
	private final Map<Short, ITypeFactory<? extends R>> types = new HashMap<>();
	private final Set<Short> reserved = new HashSet<>();
	private final Map<Class<?>, Short> registeredClasses = new HashMap<>();

	/**
	 * Registers a class to be used with this node type registry. Note that if we ever want to stop registering a type
	 * name in the future, its fully-qualified class name should be passed to reserve(...) to prevent its hashfrom being
	 * reused in the future.
	 */
	public <T extends R> void register(int typeId, ITypeFactory<T> toRegister) {
		if ((typeId & 0xFFFF0000) != 0) {
			throw new IllegalArgumentException("The typeId " + typeId + " does not fit within a short int");  //$NON-NLS-1$//$NON-NLS-2$
		}
		short shortTypeId = (short)typeId;
		String fullyQualifiedClassName = toRegister.getElementClass().getName();

		if (this.types.containsKey(typeId) || this.reserved.contains(typeId)) {
			throw new IllegalArgumentException(
					"The type id " + typeId + " for class " + fullyQualifiedClassName + " is already in use."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}

		this.types.put(shortTypeId, toRegister);
		this.registeredClasses.put(toRegister.getElementClass(), shortTypeId);
	}

	/**
	 * Reserves the given node class name, such that its hash cannot be used by any other node registered with
	 * "register". If we ever want to unregister a given Class from the type registry, its class name should be reserved
	 * using this method. Doing so will prevent its type ID from being reused by another future class.
	 */
	public void reserve(short typeId) {
		if (this.types.containsKey(typeId) || this.reserved.contains(typeId)) {
			throw new IllegalArgumentException("The type ID " + typeId + " is already in use"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		this.reserved.add(typeId);
	}

	/**
	 * Returns the class associated with the given type or null if the given type ID is not known
	 */
	public ITypeFactory<? extends R> getClassForType(short type) {
		return this.types.get(type);
	}


	public R createNode(PDOM pdom, long record, short nodeType) throws IndexException {
		ITypeFactory<? extends R> typeFactory = this.types.get(nodeType);

		return typeFactory.create(pdom, record);
	}

	public short getTypeForClass(Class<? extends R> toQuery) {
		Short classId = this.registeredClasses.get(toQuery);

		if (classId == null) {
			throw new IllegalArgumentException(toQuery.getName() + " was not registered as a node type"); //$NON-NLS-1$
		}
		return classId;
	}

	@SuppressWarnings("unchecked")
	public <T extends R> ITypeFactory<T> getTypeFactory(short nodeType) {
		ITypeFactory<T> result = (ITypeFactory<T>) this.types.get(nodeType);

		if (result == null) {
			throw new IllegalArgumentException("The node type " + nodeType  //$NON-NLS-1$
				+ " is not registered with this PDOM"); //$NON-NLS-1$
		}

		return result;
	}
}
