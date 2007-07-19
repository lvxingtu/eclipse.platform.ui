/*******************************************************************************
 * Copyright (c) 2006 Brad Reynolds and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Brad Reynolds - initial API and implementation
 *     Brad Reynolds - bug 167204
 ******************************************************************************/

package org.eclipse.core.tests.databinding.observable.list;

import junit.framework.Test;
import junit.framework.TestCase;

import org.eclipse.core.databinding.observable.IObservable;
import org.eclipse.core.databinding.observable.IObservableCollection;
import org.eclipse.core.databinding.observable.ObservableTracker;
import org.eclipse.core.databinding.observable.Realm;
import org.eclipse.core.databinding.observable.list.AbstractObservableList;
import org.eclipse.core.databinding.observable.list.ListDiff;
import org.eclipse.jface.conformance.databinding.AbstractObservableCollectionContractDelegate;
import org.eclipse.jface.conformance.databinding.ObservableCollectionContractTest;
import org.eclipse.jface.conformance.databinding.SuiteBuilder;
import org.eclipse.jface.databinding.swt.SWTObservables;
import org.eclipse.jface.tests.databinding.RealmTester;
import org.eclipse.jface.tests.databinding.RealmTester.CurrentRealm;
import org.eclipse.swt.widgets.Display;

/**
 * @since 3.2
 */
public class AbstractObservableListTest extends TestCase {
	private AbstractObservableListStub list;

	protected void setUp() throws Exception {
		RealmTester.setDefault(new CurrentRealm(true));
		list = new AbstractObservableListStub();
	}

	protected void tearDown() throws Exception {
		RealmTester.setDefault(null);
	}

	public void testFireChangeRealmChecks() throws Exception {
		RealmTester.exerciseCurrent(new Runnable() {
			public void run() {
				list.fireChange();
			}
		});
	}

	public void testFireStaleRealmChecks() throws Exception {
		RealmTester.exerciseCurrent(new Runnable() {
			public void run() {
				list.fireStale();
			}
		});
	}

	public void testFireListChangeRealmChecks() throws Exception {
		RealmTester.exerciseCurrent(new Runnable() {
			public void run() {
				list.fireListChange(null);
			}
		});
	}

	public void testListIteratorGetterCalled() throws Exception {
		final AbstractObservableListStub list = new AbstractObservableListStub();

		IObservable[] observables = ObservableTracker.runAndMonitor(
				new Runnable() {
					public void run() {
						list.listIterator();
					}
				}, null, null);

		assertEquals("length", 1, observables.length);
		assertEquals("observable", list, observables[0]);
	}

	public static Test suite() {
		return new SuiteBuilder().addTests(AbstractObservableListTest.class)
				.addParameterizedTests(ObservableCollectionContractTest.class,
						new Object[] { new Delegate() }).build();

	}

	/* package */static class Delegate extends
			AbstractObservableCollectionContractDelegate {
		private AbstractObservableListStub current;

		public IObservableCollection createObservableCollection() {
			Realm.runWithDefault(SWTObservables.getRealm(Display.getDefault()),
					new Runnable() {
						public void run() {
							current = new AbstractObservableListStub();
							current.elementType = String.class;
						}
					});
			return current;
		}
		
		public Object getElementType(IObservableCollection collection) {
			return String.class;
		}
	}

	static class AbstractObservableListStub extends AbstractObservableList {
		Object elementType;
		protected int doGetSize() {
			return 0;
		}

		public Object get(int arg0) {
			return null;
		}

		public Object getElementType() {
			return elementType;
		}

		protected void fireChange() {
			super.fireChange();
		}

		protected void fireStale() {
			super.fireStale();
		}

		protected void fireListChange(ListDiff diff) {
			super.fireListChange(diff);
		}
	}
}
