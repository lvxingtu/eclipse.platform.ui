package org.eclipse.ui.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Widget;

import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.action.MenuManager;

import org.eclipse.ui.IPageListener;
import org.eclipse.ui.IPartListener;
import org.eclipse.ui.IPartService;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.activities.ActivityManagerEvent;
import org.eclipse.ui.activities.IActivityManagerListener;
import org.eclipse.ui.commands.CommandManagerEvent;
import org.eclipse.ui.commands.IActionService;
import org.eclipse.ui.commands.IActionServiceEvent;
import org.eclipse.ui.commands.IActionServiceListener;
import org.eclipse.ui.commands.ICommandManagerListener;
import org.eclipse.ui.contexts.IContextActivationService;
import org.eclipse.ui.contexts.IContextActivationServiceEvent;
import org.eclipse.ui.contexts.IContextActivationServiceListener;
import org.eclipse.ui.keys.KeySequence;
import org.eclipse.ui.keys.KeyStroke;
import org.eclipse.ui.keys.ParseException;

import org.eclipse.ui.internal.commands.ActionService;
import org.eclipse.ui.internal.commands.CommandManager;
import org.eclipse.ui.internal.contexts.ContextActivationService;
import org.eclipse.ui.internal.keys.KeySupport;
import org.eclipse.ui.internal.util.StatusLineContributionItem;
import org.eclipse.ui.internal.util.Util;

/**
 * Controls the keyboard input into the workbench key binding architecture.
 * 
 * @since 3.0
 */
public class WorkbenchActivitiesCommandsAndRoles {

	/**
	 * A listener that makes sure that global key bindings are processed if no
	 * other listeners do any useful work.
	 */
	class OutOfOrderListener implements Listener {
		public void handleEvent(Event event) {
			// Always remove myself as a listener.
			event.widget.removeListener(event.type, this);

			/*
			 * If the event is still up for grabs, then re-route through the
			 * global key filter.
			 */
			if (event.doit) {
				List keyStrokes = generatePossibleKeyStrokes(event);
				processKeyEvent(keyStrokes, event);
			}
		}
	}

	/**
	 * A listener that makes sure that out-of-order processing occurs if no
	 * other verify listeners do any work.
	 */
	class OutOfOrderVerifyListener implements VerifyKeyListener {
		/**
		 * Checks whether any other verify listeners have triggered. If not,
		 * then it sets up the top-level out-of-order listener.
		 * 
		 * @param event
		 *            The verify event after it has been processed by all other
		 *            verify listeners; must not be <code>null</code>.
		 */
		public void verifyKey(VerifyEvent event) {
			// Always remove myself as a listener.
			Widget widget = event.widget;
			if (widget instanceof StyledText) {
				((StyledText) widget).removeVerifyKeyListener(this);
			}

			// If the event is still up for grabs, then re-route through the
			// global key filter.
			if (event.doit) {
				widget.addListener(SWT.KeyDown, outOfOrderListener);
			}
		}
	}

	static {
		initializeOutOfOrderKeys();
	}

	/**
	 * The properties key for the key strokes that should be processed out of
	 * order.
	 */
	static final String OUT_OF_ORDER_KEYS = "OutOfOrderKeys"; //$NON-NLS-1$
	/** The collection of keys that are to be processed out-of-order. */
	static KeySequence outOfOrderKeys;

	/**
	 * Generates any key strokes that are near matches to the given event. The
	 * first such key stroke is always the exactly matching key stroke.
	 * 
	 * @param event
	 *            The event from which the key strokes should be generated;
	 *            must not be <code>null</code>.
	 * @return The set of nearly matching key strokes. It is never <code>null</code>
	 *         and never empty.
	 */
	public static List generatePossibleKeyStrokes(Event event) {
		List keyStrokes = new ArrayList();
		KeyStroke keyStroke;

		keyStrokes.add(
			KeySupport.convertAcceleratorToKeyStroke(
				KeySupport.convertEventToUnmodifiedAccelerator(event)));
		keyStroke =
			KeySupport.convertAcceleratorToKeyStroke(
				KeySupport.convertEventToUnshiftedModifiedAccelerator(event));
		if (!keyStrokes.contains(keyStroke)) {
			keyStrokes.add(keyStroke);
		}
		keyStroke =
			KeySupport.convertAcceleratorToKeyStroke(
				KeySupport.convertEventToModifiedAccelerator(event));
		if (!keyStrokes.contains(keyStroke)) {
			keyStrokes.add(keyStroke);
		}
		return keyStrokes;
	}

	/**
	 * Initializes the <code>outOfOrderKeys</code> member variable using the
	 * keys defined in the properties file.
	 */
	private static void initializeOutOfOrderKeys() {
		// Get the key strokes which should be out of order.
		String keysText = WorkbenchMessages.getString(OUT_OF_ORDER_KEYS);
		outOfOrderKeys = KeySequence.getInstance();
		try {
			outOfOrderKeys = KeySequence.getInstance(keysText);
		} catch (ParseException e) {
			String message = "Could not parse out-of-order keys definition: '" + keysText + "'.  Continuing with no out-of-order keys."; //$NON-NLS-1$ //$NON-NLS-2$
			WorkbenchPlugin.log(
				message,
				new Status(IStatus.ERROR, WorkbenchPlugin.PI_WORKBENCH, 0, message, e));
		}
	}

	/**
	 * <p>
	 * Determines whether the given event represents a key press that should be
	 * handled as an out-of-order event. An out-of-order key press is one that
	 * is passed to the focus control first. Only if the focus control fails to
	 * respond will the regular key bindings get applied.
	 * </p>
	 * <p>
	 * Care must be taken in choosing which keys are chosen as out-of-order
	 * keys. This method has only been designed and test to work with the
	 * unmodified "Escape" key stroke.
	 * </p>
	 * 
	 * @param keyStrokes
	 *            The key stroke in which to look for out-of-order keys; must
	 *            not be <code>null</code>.
	 * @return <code>true</code> if the key is an out-of-order key; <code>false</code>
	 *         otherwise.
	 */
	private static boolean isOutOfOrderKey(List keyStrokes) {
		// Compare to see if one of the possible key strokes is out of order.
		Iterator keyStrokeItr = keyStrokes.iterator();
		while (keyStrokeItr.hasNext()) {
			if (outOfOrderKeys.getKeyStrokes().contains(keyStrokeItr.next())) {
				return true;
			}
		}

		return false;
	}

	IActionService actionService;

	IActionServiceListener actionServiceListener = new IActionServiceListener() {
		public void actionServiceChanged(IActionServiceEvent actionServiceEvent) {
			updateActiveCommandIdsAndActiveActivityIds();
		}
	};

	Set activeActivityIds = new HashSet();
	//IActionService activeWorkbenchWindowActionService;
	//IContextActivationService activeWorkbenchWindowContextActivationService;

	IWorkbenchPage activeWorkbenchPage;
	IActionService activeWorkbenchPageActionService;
	IContextActivationService activeWorkbenchPageContextActivationService;

	IWorkbenchPart activeWorkbenchPart;
	IActionService activeWorkbenchPartActionService;
	IContextActivationService activeWorkbenchPartContextActivationService;

	IWorkbenchWindow activeWorkbenchWindow;

	final IActivityManagerListener activityManagerListener = new IActivityManagerListener() {
		public final void activityManagerChanged(final ActivityManagerEvent activityManagerEvent) {
			updateActiveActivityIds();
		}
	};

	final ICommandManagerListener commandManagerListener = new ICommandManagerListener() {
		public final void commandManagerChanged(final CommandManagerEvent commandManagerEvent) {
			updateActiveActivityIds();
		}
	};
	IContextActivationService contextActivationService;

	IContextActivationServiceListener contextActivationServiceListener =
		new IContextActivationServiceListener() {
		public void contextActivationServiceChanged(IContextActivationServiceEvent contextActivationServiceEvent) {
			updateActiveCommandIdsAndActiveActivityIds();
		}
	};

	IInternalPerspectiveListener internalPerspectiveListener = new IInternalPerspectiveListener() {
		public void perspectiveActivated(
			IWorkbenchPage workbenchPage,
			IPerspectiveDescriptor perspectiveDescriptor) {
			updateActiveCommandIdsAndActiveActivityIds();
		}

		public void perspectiveChanged(
			IWorkbenchPage workbenchPage,
			IPerspectiveDescriptor perspectiveDescriptor,
			String changeId) {
			updateActiveCommandIdsAndActiveActivityIds();
		}

		public void perspectiveClosed(IWorkbenchPage page, IPerspectiveDescriptor perspective) {
			updateActiveCommandIdsAndActiveActivityIds();
		}

		public void perspectiveOpened(IWorkbenchPage page, IPerspectiveDescriptor perspective) {
			updateActiveCommandIdsAndActiveActivityIds();
		}
	};

	/** The listener that runs key events past the global key bindings. */
	final Listener keySequenceBindingFilter = new Listener() {
		public void handleEvent(Event event) {
			filterKeySequenceBindings(event);
		}
	};

	private KeySequence mode = KeySequence.getInstance();

	final Listener modeCleaner = new Listener() {
		public void handleEvent(Event event) {
			setMode(KeySequence.getInstance());
		}
	};
	/**
	 * The listener that allows out-of-order key processing to hook back into
	 * the global key bindings.
	 */
	final OutOfOrderListener outOfOrderListener = new OutOfOrderListener();
	/**
	 * The listener that allows out-of-order key processing on <code>StyledText</code>
	 * widgets to detect useful work in a verify key listener.
	 */
	final OutOfOrderVerifyListener outOfOrderVerifyListener = new OutOfOrderVerifyListener();

	IPageListener pageListener = new IPageListener() {
		public void pageActivated(IWorkbenchPage workbenchPage) {
			updateActiveCommandIdsAndActiveActivityIds();
		}

		public void pageClosed(IWorkbenchPage workbenchPage) {
			updateActiveCommandIdsAndActiveActivityIds();
		}

		public void pageOpened(IWorkbenchPage workbenchPage) {
			updateActiveCommandIdsAndActiveActivityIds();
		}
	};

	IPartListener partListener = new IPartListener() {
		public void partActivated(IWorkbenchPart workbenchPart) {
			updateActiveCommandIdsAndActiveActivityIds();
			updateActiveWorkbenchWindowMenuManager();
		}

		public void partBroughtToTop(IWorkbenchPart workbenchPart) {
		}

		public void partClosed(IWorkbenchPart workbenchPart) {
			updateActiveCommandIdsAndActiveActivityIds();
		}

		public void partDeactivated(IWorkbenchPart workbenchPart) {
			updateActiveCommandIdsAndActiveActivityIds();
		}

		public void partOpened(IWorkbenchPart workbenchPart) {
			updateActiveCommandIdsAndActiveActivityIds();
		}
	};

	IWindowListener windowListener = new IWindowListener() {
		public void windowActivated(IWorkbenchWindow workbenchWindow) {
			updateActiveCommandIdsAndActiveActivityIds();
			updateActiveWorkbenchWindowMenuManager();
		}

		public void windowClosed(IWorkbenchWindow workbenchWindow) {
			updateActiveCommandIdsAndActiveActivityIds();
			updateActiveWorkbenchWindowMenuManager();
		}

		public void windowDeactivated(IWorkbenchWindow workbenchWindow) {
			updateActiveCommandIdsAndActiveActivityIds();
			updateActiveWorkbenchWindowMenuManager();
		}

		public void windowOpened(IWorkbenchWindow workbenchWindow) {
			updateActiveCommandIdsAndActiveActivityIds();
			updateActiveWorkbenchWindowMenuManager();
		}
	};

	Workbench workbench;

	WorkbenchActivitiesCommandsAndRoles(Workbench workbench) {
		this.workbench = workbench;
	}

	/**
	 * <p>
	 * Launches the command matching a the typed key. This filter an incoming
	 * <code>SWT.KeyDown</code> or <code>SWT.Traverse</code> event at the
	 * level of the display (i.e., before it reaches the widgets). It does not
	 * allow processing in a dialog or if the key strokes does not contain a
	 * natural key.
	 * </p>
	 * <p>
	 * Some key strokes (defined as a property) are declared as out-of-order
	 * keys. This means that they are processed by the widget <em>first</em>.
	 * Only if the other widget listeners do no useful work does it try to
	 * process key bindings. For example, "ESC" can cancel the current widget
	 * action, if there is one, without triggering key bindings.
	 * </p>
	 * 
	 * @param event
	 *            The incoming event; must not be <code>null</code>.
	 */
	private void filterKeySequenceBindings(Event event) {
		/*
		 * Only process key strokes containing natural keys to trigger key
		 * bindings
		 */
		if ((event.keyCode & SWT.MODIFIER_MASK) != 0)
			return;

		// Don't allow dialogs to process key bindings.
		if (event.widget instanceof Control) {
			Shell shell = ((Control) event.widget).getShell();
			if (shell.getParent() != null)
				return;
		}

		// Allow special key out-of-order processing.
		List keyStrokes = generatePossibleKeyStrokes(event);
		if (isOutOfOrderKey(keyStrokes)) {
			if (event.type == SWT.KeyDown) {
				Widget widget = event.widget;
				if (widget instanceof StyledText) {
					/*
					 * KLUDGE. Some people try to do useful work in verify
					 * listeners. The way verify listeners work in SWT, we need
					 * to verify the key as well; otherwise, we can detect that
					 * useful work has been done.
					 */
					 ((StyledText) widget).addVerifyKeyListener(outOfOrderVerifyListener);
				} else {
					widget.addListener(SWT.KeyDown, outOfOrderListener);
				}
			}
			/*
			 * Otherwise, we count on a key down arriving eventually. Expecting
			 * out of order handling on Ctrl+Tab, for example, is a bad idea
			 * (stick to keys that are not window traversal keys).
			 */
		} else {
			processKeyEvent(keyStrokes, event);
		}
	}

	public IActionService getActionService() {
		if (actionService == null) {
			actionService = new ActionService();
			actionService.addActionServiceListener(actionServiceListener);
		}

		return actionService;
	}

	public IContextActivationService getContextActivationService() {
		if (contextActivationService == null) {
			contextActivationService = new ContextActivationService();
			contextActivationService.addContextActivationServiceListener(
				contextActivationServiceListener);
		}

		return contextActivationService;
	}

	private KeySequence getMode() {
		return mode;
	}

	private String getPerfectMatch(KeySequence keySequence) {
		return workbench.getCommandManager().getPerfectMatch(keySequence);
	}

	private boolean isPartialMatch(KeySequence keySequence) {
		return workbench.getCommandManager().isPartialMatch(keySequence);
	}

	private boolean isPerfectMatch(KeySequence keySequence) {
		return workbench.getCommandManager().isPerfectMatch(keySequence);
	}

	/**
	 * Processes a key press with respect to the key binding architecture. This
	 * updates the mode of the command manager, and runs the current handler
	 * for the command that matches the key sequence, if any.
	 * 
	 * @param potentialKeyStrokes
	 *            The key strokes that could potentially match, in the order of
	 *            priority; must not be <code>null</code>.
	 * @param event
	 *            The event to pass to the action; may be <code>null</code>.
	 * @return <code>true</code> if a command is executed; <code>false</code>
	 *         otherwise.
	 */
	// TODO remove event parameter once key-modified actions are removed
	public boolean press(List potentialKeyStrokes, Event event) {
		KeySequence modeBeforeKeyStroke = getMode();

		for (Iterator iterator = potentialKeyStrokes.iterator(); iterator.hasNext();) {
			KeySequence modeAfterKeyStroke =
				KeySequence.getInstance(modeBeforeKeyStroke, (KeyStroke) iterator.next());

			if (isPartialMatch(modeAfterKeyStroke)) {
				setMode(modeAfterKeyStroke);
				return true;
				
			} else if (isPerfectMatch(modeAfterKeyStroke)) {
				String commandId = getPerfectMatch(modeAfterKeyStroke);
				Map actionsById = ((CommandManager) workbench.getCommandManager()).getActionsById();
				org.eclipse.ui.commands.IAction action =
					(org.eclipse.ui.commands.IAction) actionsById.get(commandId);

				if (action != null && action.isEnabled()) {
					setMode(modeAfterKeyStroke);

					try {
						action.execute(event);
					} catch (Exception e) {
						String message = "Action for command '" + commandId + "' failed to execute properly."; //$NON-NLS-1$ //$NON-NLS-2$
						WorkbenchPlugin.log(
							message,
							new Status(IStatus.ERROR, WorkbenchPlugin.PI_WORKBENCH, 0, message, e));
					}
				}

				setMode(KeySequence.getInstance());
				return action != null || modeBeforeKeyStroke.isEmpty();
				
			}
		}

		setMode(KeySequence.getInstance());
		return false;
	}

	/**
	 * Actually performs the processing of the key event by interacting with
	 * the <code>ICommandManager</code>. If work is carried out, then the
	 * event is stopped here (i.e., <code>event.doit = false</code>).
	 * 
	 * @param keyStrokes
	 *            The set of all possible matching key strokes; must not be
	 *            <code>null</code>.
	 * @param event
	 *            The event to process; must not be <code>null</code>.
	 */
	private void processKeyEvent(List keyStrokes, Event event) {
		if (press(keyStrokes, event)) {
			switch (event.type) {
				case SWT.KeyDown :
					event.doit = false;
					break;
				case SWT.Traverse :
					event.detail = SWT.TRAVERSE_NONE;
					event.doit = true;
					break;
				default :
					}

			event.type = SWT.NONE;
		}
	}

	private void setMode(KeySequence mode) {
		if (mode == null)
			throw new NullPointerException();

		this.mode = mode;
		updateModeStatusLines();
	}

	public void updateActiveActivityIds() {
		workbench.getCommandManager().setActiveActivityIds(activeActivityIds);
	}

	void updateActiveCommandIdsAndActiveActivityIds() {
		IWorkbenchWindow currentWorkbenchWindow = workbench.getActiveWorkbenchWindow();

		if (currentWorkbenchWindow != null && !(currentWorkbenchWindow instanceof WorkbenchWindow))
			currentWorkbenchWindow = null;

		//IActionService activeWorkbenchWindowActionService =
		// activeWorkbenchWindow != null ? ((WorkbenchWindow)
		// activeWorkbenchWindow).getActionService() : null;
		//IContextActivationService
		// activeWorkbenchWindowContextActivationService =
		// activeWorkbenchWindow != null ? ((WorkbenchWindow)
		// activeWorkbenchWindow).getContextActivationService() : null;

		IWorkbenchPage currentWorkbenchPage =
			currentWorkbenchWindow != null ? currentWorkbenchWindow.getActivePage() : null;

		IActionService currentWorkbenchPageActionService =
			currentWorkbenchPage != null
				? ((WorkbenchPage) currentWorkbenchPage).getActionService()
				: null;

		IContextActivationService currentWorkbenchPageContextActivationService =
			currentWorkbenchPage != null
				? ((WorkbenchPage) currentWorkbenchPage).getContextActivationService()
				: null;

		IPartService activePartService =
			currentWorkbenchWindow != null ? currentWorkbenchWindow.getPartService() : null;
		IWorkbenchPart currentWorkbenchPart =
			activePartService != null ? activePartService.getActivePart() : null;
		IWorkbenchPartSite activeWorkbenchPartSite =
			currentWorkbenchPart != null ? currentWorkbenchPart.getSite() : null;

		IActionService currentWorkbenchPartActionService =
			activeWorkbenchPartSite != null
				? ((PartSite) activeWorkbenchPartSite).getActionService()
				: null;
		IContextActivationService currentWorkbenchPartContextActivationService =
			activeWorkbenchPartSite != null
				? ((PartSite) activeWorkbenchPartSite).getContextActivationService()
				: null;

		if (currentWorkbenchWindow != this.activeWorkbenchWindow) {
			if (this.activeWorkbenchWindow != null) {
				this.activeWorkbenchWindow.removePageListener(pageListener);
				this.activeWorkbenchWindow.getPartService().removePartListener(partListener);
				((WorkbenchWindow) this.activeWorkbenchWindow)
					.getPerspectiveService()
					.removePerspectiveListener(
					internalPerspectiveListener);
			}

			this.activeWorkbenchWindow = currentWorkbenchWindow;

			if (this.activeWorkbenchWindow != null) {
				this.activeWorkbenchWindow.addPageListener(pageListener);
				this.activeWorkbenchWindow.getPartService().addPartListener(partListener);
				((WorkbenchWindow) this.activeWorkbenchWindow)
					.getPerspectiveService()
					.addPerspectiveListener(
					internalPerspectiveListener);
			}
		}

		/*
		 * if (activeWorkbenchWindowActionService !=
		 * this.activeWorkbenchWindowActionService) { if
		 * (this.activeWorkbenchWindowActionService != null)
		 * this.activeWorkbenchWindowActionService.removeActionServiceListener(actionServiceListener);
		 * this.activeWorkbenchWindow = activeWorkbenchWindow;
		 * this.activeWorkbenchWindowActionService =
		 * activeWorkbenchWindowActionService; if
		 * (this.activeWorkbenchWindowActionService != null)
		 * this.activeWorkbenchWindowActionService.addActionServiceListener(actionServiceListener); }
		 */

		if (currentWorkbenchPageActionService != this.activeWorkbenchPageActionService) {
			if (this.activeWorkbenchPageActionService != null)
				this.activeWorkbenchPageActionService.removeActionServiceListener(
					actionServiceListener);

			this.activeWorkbenchPage = currentWorkbenchPage;
			this.activeWorkbenchPageActionService = currentWorkbenchPageActionService;

			if (this.activeWorkbenchPageActionService != null)
				this.activeWorkbenchPageActionService.addActionServiceListener(
					actionServiceListener);
		}

		if (currentWorkbenchPartActionService != this.activeWorkbenchPartActionService) {
			if (this.activeWorkbenchPartActionService != null)
				this.activeWorkbenchPartActionService.removeActionServiceListener(
					actionServiceListener);

			this.activeWorkbenchPart = currentWorkbenchPart;
			this.activeWorkbenchPartActionService = currentWorkbenchPartActionService;

			if (this.activeWorkbenchPartActionService != null)
				this.activeWorkbenchPartActionService.addActionServiceListener(
					actionServiceListener);
		}

		SortedMap actionsById = new TreeMap();
		actionsById.putAll(getActionService().getActionsById());

		//if (this.activeWorkbenchWindowActionService != null)
		//	actionsById.putAll(this.activeWorkbenchWindowActionService.getActionsById());

		if (this.activeWorkbenchWindow != null) {
			actionsById.putAll(
				((WorkbenchWindow) this.activeWorkbenchWindow).getActionsForGlobalActions());
			actionsById.putAll(
				((WorkbenchWindow) this.activeWorkbenchWindow).getActionsForActionSets());
		}

		if (this.activeWorkbenchPageActionService != null)
			actionsById.putAll(this.activeWorkbenchPageActionService.getActionsById());

		if (this.activeWorkbenchPartActionService != null)
			actionsById.putAll(this.activeWorkbenchPartActionService.getActionsById());

		((CommandManager) workbench.getCommandManager()).setActionsById(actionsById);

		/*
		 * if (activeWorkbenchWindowContextActivationService !=
		 * this.activeWorkbenchWindowContextActivationService) { if
		 * (this.activeWorkbenchWindowContextActivationService != null)
		 * this.activeWorkbenchWindowContextActivationService.removeContextActivationServiceListener(contextActivationServiceListener);
		 * this.activeWorkbenchWindow = activeWorkbenchWindow;
		 * this.activeWorkbenchWindowContextActivationService =
		 * activeWorkbenchWindowContextActivationService; if
		 * (this.activeWorkbenchWindowContextActivationService != null)
		 * this.activeWorkbenchWindowContextActivationService.addContextActivationServiceListener(contextActivationServiceListener); }
		 */

		if (currentWorkbenchPageContextActivationService
			!= this.activeWorkbenchPageContextActivationService) {
			if (this.activeWorkbenchPageContextActivationService != null)
				this
					.activeWorkbenchPageContextActivationService
					.removeContextActivationServiceListener(
					contextActivationServiceListener);

			this.activeWorkbenchPage = currentWorkbenchPage;
			this.activeWorkbenchPageContextActivationService =
				currentWorkbenchPageContextActivationService;

			if (this.activeWorkbenchPageContextActivationService != null)
				this
					.activeWorkbenchPageContextActivationService
					.addContextActivationServiceListener(
					contextActivationServiceListener);
		}

		if (currentWorkbenchPartContextActivationService
			!= this.activeWorkbenchPartContextActivationService) {
			if (this.activeWorkbenchPartContextActivationService != null)
				this
					.activeWorkbenchPartContextActivationService
					.removeContextActivationServiceListener(
					contextActivationServiceListener);

			this.activeWorkbenchPart = currentWorkbenchPart;
			this.activeWorkbenchPartContextActivationService =
				currentWorkbenchPartContextActivationService;

			if (this.activeWorkbenchPartContextActivationService != null)
				this
					.activeWorkbenchPartContextActivationService
					.addContextActivationServiceListener(
					contextActivationServiceListener);
		}

		SortedSet activeContextIds = new TreeSet();
		activeContextIds.addAll(getContextActivationService().getActiveContextIds());

		//if (this.activeWorkbenchWindowContextActivationService != null)
		//	activeContextIds.addAll(this.activeWorkbenchWindowContextActivationService.getActiveContextIds());

		if (this.activeWorkbenchPageContextActivationService != null)
			activeContextIds.addAll(
				this.activeWorkbenchPageContextActivationService.getActiveContextIds());

		if (this.activeWorkbenchPartContextActivationService != null)
			activeContextIds.addAll(
				this.activeWorkbenchPartContextActivationService.getActiveContextIds());

		Set currentActivityIds = new HashSet(activeContextIds);

		if (!Util.equals(this.activeActivityIds, currentActivityIds)) {
			this.activeActivityIds = currentActivityIds;

			updateActiveActivityIds();

			IWorkbenchWindow workbenchWindow = workbench.getActiveWorkbenchWindow();

			if (workbenchWindow instanceof WorkbenchWindow) {
				MenuManager menuManager = ((WorkbenchWindow) workbenchWindow).getMenuManager();
				menuManager.updateAll(true);
			}
		}
	}

	public void updateActiveWorkbenchWindowMenuManager() {
		IWorkbenchWindow workbenchWindow = workbench.getActiveWorkbenchWindow();

		if (workbenchWindow instanceof WorkbenchWindow) {
			MenuManager menuManager = ((WorkbenchWindow) workbenchWindow).getMenuManager();
			menuManager.update(IAction.TEXT);
		}
	}

	/**
	 * Updates the text of the given window's mode line with the given text.
	 * 
	 * @param window
	 *            the window
	 * @param text
	 *            the text
	 */
	private void updateModeLine(IWorkbenchWindow window, String text) {
		if (window instanceof WorkbenchWindow) {
			IStatusLineManager statusLine = ((WorkbenchWindow) window).getStatusLineManager();
			// @issue implicit dependency on IDE's action builder
			IContributionItem item = statusLine.find("ModeContributionItem"); //$NON-NLS-1$
			if (item instanceof StatusLineContributionItem) {
				((StatusLineContributionItem) item).setText(text);
			}
		}
	}

	/**
	 * Updates the text of the mode lines with the current mode.
	 */
	private void updateModeStatusLines() {
		// Format the mode into text.
		String text = getMode().format();

		// Update each open window's status line.
		IWorkbenchWindow[] windows = workbench.getWorkbenchWindows();
		for (int i = 0; i < windows.length; i++) {
			updateModeLine(windows[i], text);
		}
	}
}
