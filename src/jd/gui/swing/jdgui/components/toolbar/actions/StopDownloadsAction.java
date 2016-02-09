package jd.gui.swing.jdgui.components.toolbar.actions;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import org.appwork.uio.UIOManager;
import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.swing.EDTRunner;
import org.appwork.utils.swing.dialog.Dialog;
import org.jdownloader.controlling.contextmenu.ActionContext;
import org.jdownloader.controlling.contextmenu.Customizer;
import org.jdownloader.gui.IconKey;
import org.jdownloader.gui.toolbar.action.AbstractToolBarAction;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.images.AbstractIcon;
import org.jdownloader.translate._JDT;

import jd.controlling.downloadcontroller.DownloadLinkCandidate;
import jd.controlling.downloadcontroller.DownloadLinkCandidateResult;
import jd.controlling.downloadcontroller.DownloadWatchDog;
import jd.controlling.downloadcontroller.DownloadWatchDogProperty;
import jd.controlling.downloadcontroller.SingleDownloadController;
import jd.controlling.downloadcontroller.event.DownloadWatchdogListener;
import jd.gui.swing.jdgui.JDGui;
import jd.gui.swing.jdgui.WarnLevel;

public class StopDownloadsAction extends AbstractToolBarAction implements DownloadWatchdogListener, ActionContext {

    public StopDownloadsAction() {
        setIconKey(IconKey.ICON_MEDIA_PLAYBACK_STOP);
        setEnabled(false);
        DownloadWatchDog.getInstance().getEventSender().addListener(this, true);
        DownloadWatchDog.getInstance().notifyCurrentState(this);
        setName(_GUI.T.StopDownloadsAction());
        setAccelerator(KeyEvent.VK_S);

    }

    @Override
    public void initContextDefaults() {
        setHideIfDownloadsAreStopped(false);
    }

    public void actionPerformed(ActionEvent e) {
        if (DownloadWatchDog.getInstance().getStateMachine().hasPassed(DownloadWatchDog.STOPPING_STATE)) {
            return;
        }
        int count = DownloadWatchDog.getInstance().getNonResumableRunningCount();
        if (count > 0) {
            long bytesToLoose = DownloadWatchDog.getInstance().getNonResumableBytes();
            WarnLevel level = WarnLevel.LOW;
            if (bytesToLoose > 0) {
                level = WarnLevel.SEVERE;
            }
            if (JDGui.bugme(level)) {
                if (!UIOManager.I().showConfirmDialog(Dialog.STYLE_SHOW_DO_NOT_DISPLAY_AGAIN | UIOManager.LOGIC_DONT_SHOW_AGAIN_IGNORES_CANCEL, _GUI.T.lit_are_you_sure(), _GUI.T.StopDownloadsAction_run_msg_(SizeFormatter.formatBytes(bytesToLoose), count), new AbstractIcon(IconKey.ICON_STOP, 32), _GUI.T.lit_yes(), _GUI.T.lit_no())) {
                    return;
                }
            }
        }
        DownloadWatchDog.getInstance().stopDownloads();
    }

    private boolean            hideIfDownloadsAreStopped     = false;
    public static final String HIDE_IF_DOWNLOADS_ARE_STOPPED = "HideIfDownloadsAreStopped";

    public static String getHideIfDownloadsAreStoppedTranslation() {

        return _JDT._.PauseDownloadsAction_getHideIfDownloadsAreStoppedTranslation();
    }

    @Customizer(link = "#getHideIfDownloadsAreStoppedTranslation")
    public boolean isHideIfDownloadsAreStopped() {
        return hideIfDownloadsAreStopped;
    }

    public void setHideIfDownloadsAreStopped(boolean showIfDownloadsAreRunning) {
        this.hideIfDownloadsAreStopped = showIfDownloadsAreRunning;
        if (isHideIfDownloadsAreStopped() && !DownloadWatchDog.getInstance().isRunning()) {
            setVisible(false);
        } else {
            setVisible(true);
        }
    }

    @Override
    public String createTooltip() {
        return _GUI.T.action_stop_downloads_tooltip();
    }

    @Override
    public void onDownloadWatchdogStateIsStopping() {
        new EDTRunner() {

            @Override
            protected void runInEDT() {
                setEnabled(false);

            }
        };

    }

    @Override
    public void onDownloadWatchdogStateIsStopped() {
        new EDTRunner() {

            @Override
            protected void runInEDT() {
                setEnabled(false);
                if (isHideIfDownloadsAreStopped()) {
                    setVisible(false);
                }
            }
        };
    }

    @Override
    public void onDownloadWatchdogStateIsRunning() {
        new EDTRunner() {

            @Override
            protected void runInEDT() {
                setEnabled(true);
                setVisible(true);
            }
        };
    }

    @Override
    public void onDownloadWatchdogStateIsPause() {
    }

    @Override
    public void onDownloadWatchdogStateIsIdle() {
        new EDTRunner() {

            @Override
            protected void runInEDT() {
                setEnabled(false);
                if (isHideIfDownloadsAreStopped()) {
                    setVisible(false);
                }
            }
        };
    }

    @Override
    public void onDownloadWatchdogDataUpdate() {
    }

    @Override
    public void onDownloadControllerStart(SingleDownloadController downloadController, DownloadLinkCandidate candidate) {
    }

    @Override
    public void onDownloadControllerStopped(SingleDownloadController downloadController, DownloadLinkCandidate candidate, DownloadLinkCandidateResult result) {
    }

    @Override
    public void onDownloadWatchDogPropertyChange(DownloadWatchDogProperty propertyChange) {
    }

}
