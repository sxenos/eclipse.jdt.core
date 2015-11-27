package org.eclipse.jdt.internal.core.pdom.java;

import org.eclipse.jdt.internal.core.pdom.PDOM;
import org.eclipse.jdt.internal.core.pdom.db.IString;
import org.eclipse.jdt.internal.core.pdom.field.FieldManyToOne;
import org.eclipse.jdt.internal.core.pdom.field.FieldOneToMany;
import org.eclipse.jdt.internal.core.pdom.field.FieldString;
import org.eclipse.jdt.internal.core.pdom.field.StructDef;

/**
 * Represents a type signature that is anything other than a trivial reference to a concrete
 * type. If a type reference includes annotations, generic arguments, wildcards, or is a
 * type variable, this object represents it.
 */
public class PDOMComplexTypeSignature extends PDOMTypeSignature {
	public static final FieldString VARIABLE_IDENTIFIER;
	public static final FieldManyToOne<PDOMTypeId> RAW_TYPE;
	public static final FieldOneToMany<PDOMAnnotation> ANNOTATIONS;
	public static final FieldOneToMany<PDOMTypeArgument> TYPE_ARGUMENTS;

	@SuppressWarnings("hiding")
	public static final StructDef<PDOMComplexTypeSignature> type;

	static {
		type = StructDef.create(PDOMComplexTypeSignature.class, PDOMTypeSignature.type);
		VARIABLE_IDENTIFIER = type.addString();
		RAW_TYPE = FieldManyToOne.create(type, PDOMTypeId.USED_AS_COMPLEX_TYPE);
		ANNOTATIONS = FieldOneToMany.create(type, PDOMAnnotation.PARENT_TYPE_SIGNATURE);
		TYPE_ARGUMENTS = FieldOneToMany.create(type, PDOMTypeArgument.PARENT);
		type.useStandardRefCounting().done();
	}

	public PDOMComplexTypeSignature(PDOM pdom, long record) {
		super(pdom, record);
	}

	public PDOMComplexTypeSignature(PDOM pdom) {
		super(pdom);
	}

	@Override
	public PDOMTypeId getRawType() {
		return RAW_TYPE.get(getPDOM(), this.address);
	}

	/**
	 * If this type is a variable, this returns the identifier
	 * 
	 * @return
	 */
	public IString getVariableIdentifier() {
		return VARIABLE_IDENTIFIER.get(getPDOM(), this.address);
	}
}
