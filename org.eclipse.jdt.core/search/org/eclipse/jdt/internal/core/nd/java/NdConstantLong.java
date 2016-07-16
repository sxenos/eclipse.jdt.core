package org.eclipse.jdt.internal.core.nd.java;

import org.eclipse.jdt.internal.compiler.impl.Constant;
import org.eclipse.jdt.internal.compiler.impl.LongConstant;
import org.eclipse.jdt.internal.core.nd.Nd;
import org.eclipse.jdt.internal.core.nd.field.FieldLong;
import org.eclipse.jdt.internal.core.nd.field.StructDef;

/**
 * @since 3.12
 */
public final class NdConstantLong extends NdConstant {
	public static final FieldLong VALUE;

	@SuppressWarnings("hiding")
	public static StructDef<NdConstantLong> type;

	static {
		type = StructDef.create(NdConstantLong.class, NdConstant.type);
		VALUE = type.addLong();
		type.done();
	}

	public NdConstantLong(Nd nd, long address) {
		super(nd, address);
	}

	protected NdConstantLong(Nd nd) {
		super(nd);
	}

	public static NdConstantLong create(Nd nd, long value) {
		NdConstantLong result = new NdConstantLong(nd);
		result.setValue(value);
		return result;
	}

	public void setValue(long value) {
		VALUE.put(getNd(), this.address, value);
	}

	public long getValue() {
		return VALUE.get(getNd(), this.address);
	}

	@Override
	public Constant getConstant() {
		return LongConstant.fromValue(getValue());
	}
}
