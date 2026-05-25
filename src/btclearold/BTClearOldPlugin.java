package btclearold;

import com.biglybt.pif.Plugin;
import com.biglybt.pif.PluginException;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.UnloadablePlugin;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadException;
import com.biglybt.pif.download.DownloadManager;
import com.biglybt.pif.download.DownloadRemovalVetoException;
import com.biglybt.pif.logging.LoggerChannel;
import com.biglybt.pif.ui.config.ActionParameter;
import com.biglybt.pif.ui.config.BooleanParameter;
import com.biglybt.pif.ui.config.IntParameter;
import com.biglybt.pif.ui.config.Parameter;
import com.biglybt.pif.ui.config.ParameterListener;
import com.biglybt.pif.ui.config.StringListParameter;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.biglybt.pif.utils.LocaleUtilities;
import com.biglybt.pif.utils.UTTimer;
import com.biglybt.pif.utils.UTTimerEvent;
import com.biglybt.pif.utils.UTTimerEventPerformer;

public class BTClearOldPlugin implements UnloadablePlugin {

    private static final long MS_PER_HOUR = 60L * 60L * 1000L;
    private static final long MS_PER_DAY  = 24L * MS_PER_HOUR;

    private static final String UNIT_HOURS = "hours";
    private static final String UNIT_DAYS  = "days";

    private PluginInterface pi;
    private LoggerChannel log;
    private BasicPluginConfigModel configModel;

    private BooleanParameter    pEnabled;
    private IntParameter        pDeleteAfterAmount;
    private StringListParameter pDeleteAfterUnit;
    private IntParameter        pCheckIntervalMinutes;
    private BooleanParameter pOnlyIfStopped;
    private BooleanParameter pDeleteData;
    private BooleanParameter pDeleteTorrentFile;
    private BooleanParameter pDryRun;

    private UTTimer timer;
    private final Object timerLock = new Object();
    private volatile boolean unloaded;

    @Override
    public void initialize(PluginInterface pluginInterface) throws PluginException {
        this.pi = pluginInterface;
        this.log = pi.getLogger().getChannel("BTClearOld");
        log.setDiagnostic();

        LocaleUtilities loc = pi.getUtilities().getLocaleUtilities();
        loc.integrateLocalisedMessageBundle("btclearold.Messages");

        configModel = pi.getUIManager().createBasicPluginConfigModel("plugins", "btclearold.name");

        pEnabled              = configModel.addBooleanParameter2("btclearold.enabled",              "btclearold.enabled",              true);
        pDeleteAfterAmount    = configModel.addIntParameter2    ("btclearold.delete_after_amount",  "btclearold.delete_after_amount",  12);
        pDeleteAfterUnit      = configModel.addStringListParameter2(
                "btclearold.delete_after_unit", "btclearold.delete_after_unit",
                new String[] { UNIT_HOURS, UNIT_DAYS },
                new String[] { loc.getLocalisedMessageText("btclearold.unit.hours"),
                               loc.getLocalisedMessageText("btclearold.unit.days") },
                UNIT_HOURS);
        pCheckIntervalMinutes = configModel.addIntParameter2    ("btclearold.check_interval_min",   "btclearold.check_interval_min",   60);
        pOnlyIfStopped        = configModel.addBooleanParameter2("btclearold.only_if_stopped",      "btclearold.only_if_stopped",      false);
        pDeleteTorrentFile    = configModel.addBooleanParameter2("btclearold.delete_torrent_file",  "btclearold.delete_torrent_file",  true);
        pDeleteData           = configModel.addBooleanParameter2("btclearold.delete_data",          "btclearold.delete_data",          false);
        pDryRun               = configModel.addBooleanParameter2("btclearold.dry_run",              "btclearold.dry_run",              false);

        ActionParameter pCleanNow = configModel.addActionParameter2("btclearold.clean_now.label", "btclearold.clean_now.button");
        pCleanNow.addListener(new ParameterListener() {
            @Override public void parameterChanged(Parameter param) {
                Thread t = new Thread(() -> safeScan(), "BTClearOld-manual");
                t.setDaemon(true);
                t.start();
            }
        });

        ParameterListener restartListener = new ParameterListener() {
            @Override public void parameterChanged(Parameter param) { restartTimer(); }
        };
        pEnabled.addListener(restartListener);
        pCheckIntervalMinutes.addListener(restartListener);

        startTimer();

        log.log("BTClearOld initialized (every " + pCheckIntervalMinutes.getValue()
                + " min, removing completed older than " + pDeleteAfterAmount.getValue() + " " + pDeleteAfterUnit.getValue() + ")");
    }

    private void startTimer() {
        synchronized (timerLock) {
            stopTimerLocked();
            if (unloaded || !pEnabled.getValue()) return;

            int minutes = Math.max(1, pCheckIntervalMinutes.getValue());
            long intervalMs = minutes * 60L * 1000L;

            timer = pi.getUtilities().createTimer("BTClearOld scanner", true);
            timer.addPeriodicEvent(intervalMs, new UTTimerEventPerformer() {
                @Override public void perform(UTTimerEvent event) { safeScan(); }
            });
        }
    }

    private void restartTimer() {
        startTimer();
    }

    private void stopTimerLocked() {
        if (timer != null) {
            try { timer.destroy(); } catch (Throwable ignored) {}
            timer = null;
        }
    }

    private void safeScan() {
        try {
            scan();
        } catch (Throwable t) {
            log.log("Scan failed", t);
        }
    }

    private void scan() {
        if (unloaded || !pEnabled.getValue()) return;

        int amount = pDeleteAfterAmount.getValue();
        if (amount <= 0) {
            log.log("Skipping scan: delete_after_amount = " + amount);
            return;
        }
        String unit = pDeleteAfterUnit.getValue();
        long unitMs = UNIT_DAYS.equals(unit) ? MS_PER_DAY : MS_PER_HOUR;
        long thresholdMs = amount * unitMs;
        long cutoff = System.currentTimeMillis() - thresholdMs;
        boolean dryRun         = pDryRun.getValue();
        boolean onlyIfStopped  = pOnlyIfStopped.getValue();
        boolean deleteData     = pDeleteData.getValue();
        boolean deleteTorrent  = pDeleteTorrentFile.getValue();

        DownloadManager dm = pi.getDownloadManager();
        Download[] downloads = dm.getDownloads();

        int considered = 0;
        int removed    = 0;

        for (Download d : downloads) {
            if (d == null) continue;
            if (!d.isComplete(false)) continue;

            considered++;

            long completedTime = d.getStats().getTimeStartedSeeding();
            if (completedTime <= 0) {
                completedTime = d.getCreationTime();
            }
            if (completedTime <= 0 || completedTime > cutoff) continue;

            int state = d.getState();
            if (onlyIfStopped && state != Download.ST_STOPPED && state != Download.ST_ERROR) continue;

            long ageMs    = System.currentTimeMillis() - completedTime;
            long ageHours = ageMs / MS_PER_HOUR;
            String ageStr = ageHours >= 48 ? (ageMs / MS_PER_DAY) + " days" : ageHours + " hours";

            if (dryRun) {
                log.log("[DRY-RUN] Would remove: '" + d.getName() + "' (completed " + ageStr + " ago)");
                continue;
            }

            try {
                if (state != Download.ST_STOPPED && state != Download.ST_ERROR) {
                    try { d.stop(); } catch (DownloadException ignored) {}
                }
                d.remove(deleteTorrent, deleteData);
                removed++;
                log.log("Removed '" + d.getName() + "' (completed " + ageStr + " ago)");
            } catch (DownloadRemovalVetoException e) {
                log.log("Removal vetoed for '" + d.getName() + "': " + e.getMessage());
            } catch (DownloadException e) {
                log.log("Could not remove '" + d.getName() + "': " + e.getMessage());
            } catch (Throwable t) {
                log.log("Error removing '" + d.getName() + "'", t);
            }
        }

        log.log("Scan: " + considered + " completed downloads considered, " + removed + " removed"
                + (dryRun ? " (dry-run)" : ""));
    }

    @Override
    public void unload() throws PluginException {
        unloaded = true;
        synchronized (timerLock) {
            stopTimerLocked();
        }
        if (configModel != null) {
            try { configModel.destroy(); } catch (Throwable ignored) {}
            configModel = null;
        }
    }
}
