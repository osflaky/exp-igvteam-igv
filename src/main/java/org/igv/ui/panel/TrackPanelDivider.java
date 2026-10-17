package org.igv.ui.panel;

import org.igv.Globals;
import org.igv.prefs.Constants;
import org.igv.prefs.IGVPreferences;
import org.igv.prefs.PreferencesManager;
import org.igv.track.Track;
import org.igv.ui.IGV;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * A thin draggable divider placed below a TrackPanelScrollPane. Dragging works
 * like Google Sheets row resizing:
 * <ul>
 *   <li><b>Drag down</b> – increases the height of the track above; everything
 *       below simply shifts down.</li>
 *   <li><b>Drag up</b> – decreases the height of the track above (down to
 *       {@link Track#getMinimumHeight()}); everything below simply shifts up.</li>
 * </ul>
 * Only the track above the divider is resized; tracks below are never modified.
 * Height changes are persisted via {@link Track#setHeight(int)}.
 * <p>
 * If the immediate pane above this divider is invisible (preferred height&nbsp;≤&nbsp;0),
 * the divider walks upward through siblings to find the nearest visible pane. If no
 * visible pane exists above, the divider hides itself.
 * <p>
 * The divider doubles as the visible border between tracks, so its height and color come from
 * the {@link Constants#TRACK_BORDER_WIDTH} and {@link Constants#TRACK_BORDER_COLOR} preferences.
 * The border is normally only a pixel or two tall, far too small to aim at, so the area the
 * divider answers mouse events for is independent of the area it paints: {@link #contains}
 * extends {@link #GRAB_MARGIN} pixels above and below the painted border. The track above yields
 * its bottom edge to make room (see {@link TrackPanelScrollPane#contains}); the track below is
 * checked after this divider and so needs no cooperation.
 */
public class TrackPanelDivider extends JPanel {

    /**
     * Number of pixels above and below the painted border that still count as part of the
     * divider for mouse purposes, so the drag handle stays usable at any border width.
     */
    public static final int GRAB_MARGIN = 3;

    /**
     * The border is always drawn.  A zero-height divider would leave nothing to grab, and the
     * grab margins of adjacent dividers would run together, so one pixel is the minimum.
     */
    private static final int MIN_BORDER_WIDTH = 1;

    /**
     * Fallback colors used when {@link Constants#TRACK_BORDER_COLOR} has not been set explicitly.
     * The preference default (240,240,240) is tuned for the light theme, so in dark mode an
     * equally subtle dark value is substituted unless the user has chosen a color.
     */
    private static final Color DEFAULT_DARK_COLOR = new Color(60, 60, 60);

    /**
     * Height of the border drawn between track panels, from user preferences.
     */
    public static int getDividerHeight() {
        IGVPreferences prefs = PreferencesManager.getPreferences();
        return Math.max(MIN_BORDER_WIDTH, prefs.getAsInt(Constants.TRACK_BORDER_WIDTH));
    }

    /**
     * Color of the gap between track panels, from user preferences.
     */
    public static Color getDividerColor() {
        IGVPreferences prefs = PreferencesManager.getPreferences();
        if (Globals.isDarkMode() && !prefs.hasExplicitValue(Constants.TRACK_BORDER_COLOR)) {
            return DEFAULT_DARK_COLOR;
        }
        return prefs.getAsColor(Constants.TRACK_BORDER_COLOR);
    }

    /**
     * The immediate pane above this divider.
     */
    private final TrackPanelScrollPane abovePane;

    private int dragStartY;
    private int originalAboveHeight;

    /**
     * @param abovePane the scroll pane above this divider, or {@code null} if none
     * @param belowPane the scroll pane below this divider, or {@code null} for the trailing divider
     */
    public TrackPanelDivider(TrackPanelScrollPane abovePane, TrackPanelScrollPane belowPane) {
        this.abovePane = abovePane;

        setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
        setOpaque(false);   // Painted explicitly in paintComponent, color is preference driven

        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                TrackPanelScrollPane effective = getEffectiveAbovePane();
                if (effective == null) return;
                if (PreferencesManager.getPreferences().getAsBoolean(Constants.SHOW_SINGLE_TRACK_PANE_KEY)) {
                    return;
                }
                dragStartY = e.getYOnScreen();
                originalAboveHeight = getTrackHeight(effective);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                TrackPanelScrollPane effective = getEffectiveAbovePane();
                if (effective == null) return;
                if (PreferencesManager.getPreferences().getAsBoolean(Constants.SHOW_SINGLE_TRACK_PANE_KEY)) {
                    return;
                }
                int delta = e.getYOnScreen() - dragStartY;

                int minAbove = getTrackMinimumHeight(effective);
                int newAboveHeight = Math.max(minAbove, originalAboveHeight + delta);
                setTrackHeight(effective, newAboveHeight);

                // Repaint the parent container so vertical divider lines are redrawn
                Container parent = TrackPanelDivider.this.getParent();
                if (parent != null) {
                    parent.repaint();
                }
            }
        };

        addMouseListener(mouseAdapter);
        addMouseMotionListener(mouseAdapter);

        // Accept TrackPanel drops on the divider — place the dropped track after the track above this divider
        new DropTarget(this, DnDConstants.ACTION_MOVE, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent dtde) {
                handleTrackPanelDrop(dtde);
            }
        }, true);
    }

    /**
     * Handles a TrackPanel being dropped onto this divider. The dropped track is
     * placed immediately after the track above this divider (i.e. at the divider's
     * position in the track list).
     */
    private void handleTrackPanelDrop(DropTargetDropEvent dtde) {
        try {
            DataFlavor trackPanelFlavor = TrackPanel.getTrackPanelDataFlavor();
            Transferable transferable = dtde.getTransferable();

            if (!transferable.isDataFlavorSupported(trackPanelFlavor)) {
                // Not a TrackPanel drag — delegate to MainPanel for file/URL drops
                MainPanel mainPanel = IGV.getInstance().getMainPanel();
                mainPanel.drop(dtde);
                return;
            }

            dtde.acceptDrop(DnDConstants.ACTION_MOVE);
            Object transferableObj = transferable.getTransferData(trackPanelFlavor);
            if (transferableObj == null) {
                dtde.dropComplete(false);
                return;
            }

            TrackPanel droppedPanel = (TrackPanel) transferableObj;
            MainPanel mainPanel = IGV.getInstance().getMainPanel();
            List<TrackPanel> panels = mainPanel.getTrackPanels();

            // Find the track panel above this divider
            TrackPanel aboveTrackPanel = abovePane != null ? abovePane.getTrackPanel() : null;

            // If dropped right back next to itself, do nothing
            if (droppedPanel == aboveTrackPanel) {
                dtde.dropComplete(true);
                return;
            }

            // Build the new order using direct scroll pane references
            List<TrackPanelScrollPane> orderedPanes = new ArrayList<>(panels.size());
            for (TrackPanel panel : panels) {
                if (panel == droppedPanel) continue; // skip dropped panel in its old position
                orderedPanes.add(panel.getScrollPane());
                if (panel == aboveTrackPanel) {
                    orderedPanes.add(droppedPanel.getScrollPane()); // insert after abovePanel
                }
            }
            // If abovePane is null (divider is at top), insert at position 0
            if (aboveTrackPanel == null) {
                TrackPanelScrollPane droppedSp = droppedPanel.getScrollPane();
                if (!orderedPanes.contains(droppedSp)) {
                    orderedPanes.add(0, droppedSp);
                }
            }

            mainPanel.reorderPanels(orderedPanes);
            mainPanel.updateMovedTrackOrder(droppedPanel);
            dtde.dropComplete(true);

        } catch (Exception ex) {
            dtde.rejectDrop();
        }
    }

    /**
     * Returns the nearest visible (preferred height &gt; 0) TrackPanelScrollPane
     * above this divider, walking upward through siblings if the immediate pane
     * above has zero height. Returns {@code null} if no visible pane is found.
     */
    private TrackPanelScrollPane getEffectiveAbovePane() {
        Container parent = getParent();
        if (parent == null) return isPaneVisible(abovePane) ? abovePane : null;

        Component[] siblings = parent.getComponents();
        // Find our index
        int myIndex = -1;
        for (int i = 0; i < siblings.length; i++) {
            if (siblings[i] == this) {
                myIndex = i;
                break;
            }
        }
        if (myIndex < 0) return isPaneVisible(abovePane) ? abovePane : null;

        // Walk backwards from just before this divider to find the nearest visible pane
        for (int i = myIndex - 1; i >= 0; i--) {
            if (siblings[i] instanceof TrackPanelScrollPane) {
                TrackPanelScrollPane pane = (TrackPanelScrollPane) siblings[i];
                if (isPaneVisible(pane)) {
                    return pane;
                }
            }
        }
        return null;
    }

    /**
     * Returns {@code true} if the given pane is non-null and contains a visible
     * track (i.e. the track's height is greater than zero).
     */
    private static boolean isPaneVisible(TrackPanelScrollPane pane) {
        if (pane == null) return false;
        Track track = pane.getTrackPanel().getTrack();
        return track != null && track.getHeight() > 0;
    }

    private static int getTrackHeight(TrackPanelScrollPane pane) {
        Track track = pane.getTrackPanel().getTrack();
        return track != null ? track.getHeight() : 0;
    }

    private static int getTrackMinimumHeight(TrackPanelScrollPane pane) {
        Track track = pane.getTrackPanel().getTrack();
        return track != null ? track.getMinimumHeight() : 10;
    }

    private static void setTrackHeight(TrackPanelScrollPane pane, int height) {
        Track track = pane.getTrackPanel().getTrack();
        if (track != null) {
            track.setHeight(height);
        }
    }

    /**
     * Returns {@code true} if this divider should be shown. The divider is hidden
     * when its immediate above pane is not visible (preferred height ≤ 0), because
     * the divider before the invisible pane already provides resize control for
     * the nearest visible track above.
     */
    private boolean shouldBeVisible() {
        return isPaneVisible(abovePane);
    }

    /**
     * Extends the divider's mouse-event area {@link #GRAB_MARGIN} pixels beyond the border it
     * paints, so the drag handle can be grabbed however thin the border is. Swing resolves both
     * the event target and the cursor through this method, so the resize cursor appears over the
     * whole band. Painting is unaffected — it is clipped to the component bounds.
     */
    @Override
    public boolean contains(int x, int y) {
        if (!shouldBeVisible()) return false;
        return x >= 0 && x < getWidth() &&
                y >= -GRAB_MARGIN && y < getHeight() + GRAB_MARGIN;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(Integer.MAX_VALUE, height());
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, height());
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, height());
    }

    private int height() {
        return shouldBeVisible() ? getDividerHeight() : 0;
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (!shouldBeVisible()) return;

        super.paintComponent(g);

        g.setColor(getDividerColor());
        g.fillRect(0, 0, getWidth(), getHeight());
    }
}

