package org.eclipse.ui.views.navigator;

import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.plugin.AbstractUIPlugin;

public interface IResourceNavigatorPart extends IViewPart{
	
/**
 * Returns the tree viewer which shows the resource hierarchy.
 */
TreeViewer getResourceViewer();

/**
 * Returns the pattern filter for this navigator.
 *
 * @return the pattern filter
 */
ResourcePatternFilter getPatternFilter();

/**
 * Returns the navigator's plugin.
 */
AbstractUIPlugin getPlugin();

/**
 * Set the current sorter.
 */
void setResourceSorter(ResourceSorter sorter);

/**
 * Get the resource sorter used by the receiver.
 */

ResourceSorter getResourceSorter();

/**
 * Returns the shell to use for opening dialogs.
 * Used in this class, and in the actions.
 */
Shell getShell();

}
	
