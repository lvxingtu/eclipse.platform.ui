/*******************************************************************************
 * Copyright (c) 2008, 2015 Matthew Hall and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Matthew Hall - initial API and implementation (bug 194734)
 *     Matthew Hall - bug 195222, 264307, 265561
 ******************************************************************************/

package org.eclipse.core.internal.databinding.beans;

import java.beans.PropertyDescriptor;

import org.eclipse.core.databinding.observable.Diffs;
import org.eclipse.core.databinding.observable.IDiff;
import org.eclipse.core.databinding.property.INativePropertyListener;
import org.eclipse.core.databinding.property.ISimplePropertyListener;
import org.eclipse.core.databinding.property.value.SimpleValueProperty;

/**
 * @since 3.3
 *
 */
public class BeanValueProperty extends SimpleValueProperty {
	private final PropertyDescriptor propertyDescriptor;
	private final Class valueType;

	/**
	 * @param propertyDescriptor
	 * @param valueType
	 */
	public BeanValueProperty(PropertyDescriptor propertyDescriptor,
			Class valueType) {
		this.propertyDescriptor = propertyDescriptor;
		this.valueType = valueType == null ? propertyDescriptor
				.getPropertyType() : valueType;
	}

	@Override
	public Object getValueType() {
		return valueType;
	}

	@Override
	protected Object doGetValue(Object source) {
		return BeanPropertyHelper.readProperty(source, propertyDescriptor);
	}

	@Override
	protected void doSetValue(Object source, Object value) {
		BeanPropertyHelper.writeProperty(source, propertyDescriptor, value);
	}

	@Override
	public INativePropertyListener adaptListener(
			final ISimplePropertyListener listener) {
		return new BeanPropertyListener(this, propertyDescriptor, listener) {
			@Override
			protected IDiff computeDiff(Object oldValue, Object newValue) {
				return Diffs.createValueDiff(oldValue, newValue);
			}
		};
	}

	@Override
	public String toString() {
		String s = BeanPropertyHelper.propertyName(propertyDescriptor);
		if (valueType != null)
			s += "<" + BeanPropertyHelper.shortClassName(valueType) + ">"; //$NON-NLS-1$//$NON-NLS-2$
		return s;
	}
}
