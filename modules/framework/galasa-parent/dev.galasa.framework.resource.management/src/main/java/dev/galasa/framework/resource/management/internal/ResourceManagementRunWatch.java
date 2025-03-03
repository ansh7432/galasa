/*
 * Copyright contributors to the Galasa project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package dev.galasa.framework.resource.management.internal;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import dev.galasa.framework.spi.DynamicStatusStoreException;
import dev.galasa.framework.spi.FrameworkException;
import dev.galasa.framework.spi.IDynamicStatusStoreService;
import dev.galasa.framework.spi.IDynamicStatusStoreWatcher;
import dev.galasa.framework.spi.IDynamicStatusStoreWatcher.Event;
import dev.galasa.framework.spi.IFramework;
import dev.galasa.framework.spi.IResourceManagementProvider;

public class ResourceManagementRunWatch  {

    private final Log logger = LogFactory.getLog(this.getClass());
    private DSSWatcher watcher ;

    /**
     * Events which are spotted by the dss, and we want to process.
     */
    class DssEvent {
        private final Event eventType;
        private final String runName;
        private final String newValue;
        private final String oldValue;
        
        public DssEvent( Event eventType, String runName, String oldValue , String newValue) {
            this.eventType = eventType ;
            this.runName = runName ;
            this.newValue = newValue;
            this.oldValue = oldValue ;

            logger.debug(this.toString());
        }


        @Override
        public String toString() {
            return "Dss event created: type:"+eventType+" runName:"+runName+" oldValue:"+oldValue+" newValue:"+newValue;
        }

        public String getRunName() {
            return this.runName;
        }
        public Event getEventType() {
            return this.eventType;
        }

        public String getNewValue() {
            return this.newValue;
        }

        public String getOldValue() {
            return this.oldValue;
        }
    }           

    /**
     * A runnable thread which processes events coming out of DSS.
     * 
     * It can be scheduled to fire regularly to check the queue of events coming from DSS.
     * 
     * It reads all the events in the dss queue and processes them all before returning.
     */
    class WatchEventProcessor implements Runnable {

        private final BlockingQueue<DssEvent> queue;
        private final ArrayList<IResourceManagementProvider> resourceManagementProviders ;

        public WatchEventProcessor(BlockingQueue<DssEvent> queue, ArrayList<IResourceManagementProvider> resourceManagementProviders) {
            this.queue = queue;
            this.resourceManagementProviders = resourceManagementProviders ;
        }

        /**
         * Called every few seconds on a fixed schedule, so we can look for any dss events we want to process, 
         * and process them on this thread.
         */
        @Override
        public void run() {

            try {
                boolean isDone = false;

                while(!isDone) {

                    // queue.take() blocks momentarily.
                    DssEvent dssEvent = queue.take();
                    if (dssEvent == null) {
                        isDone = true;
                    } else {

                        Event event = dssEvent.getEventType();
                        String runName = dssEvent.getRunName();
                        String newValue = dssEvent.getNewValue();

                        if (event == Event.DELETE) {
                            logger.debug("Detected deleted run " + runName);
                            this.runFinishedOrDeleted(runName, this.resourceManagementProviders);
                        } else {
                
                            if ("Finished".equals(newValue)) {
                                logger.debug("Detected finished run " + runName);
                                this.runFinishedOrDeleted(runName, this.resourceManagementProviders);
                            }
                        }
                    }
                }
            } catch( Exception ex ) {
                logger.warn("Exception caught and ignored in WatchEventProcessor: "+ex);
            }
        }

        private void runFinishedOrDeleted(String runName, ArrayList<IResourceManagementProvider> resourceManagementProviders) {
            for (IResourceManagementProvider provider : resourceManagementProviders) {
                logger.debug("About to call runFinishedOrDeleted() for provider "+provider.getClass().getCanonicalName());
                provider.runFinishedOrDeleted(runName);
                logger.debug("Returned from call runFinishedOrDeleted() for provider "+provider.getClass().getCanonicalName());
            }
        }
    }


    /**
     * Something which watches the DSS for events which occur.
     * 
     * Any events which occur are added to an event queue to be processed later.
     * 
     * This watcher must not block for long, as it needs to get execution back to etcd.
     */
    class DSSWatcher implements IDynamicStatusStoreWatcher {
        // \Q is the start of a literal string
        // \E is the end of a literal string
        // So it matches something like this:
        // run.U4657.status
        // TODO: Needs unit tests.
        // Why are the '.' characters not escaped in this, as '.' has special meaning in a regex. ?
        private final Pattern runTestPattern = Pattern.compile("^\\Qrun.\\E(\\w+)\\Q.status\\E$");
        private final BlockingQueue<DssEvent> eventQueue ;
        private UUID watchID;
        private final IDynamicStatusStoreService dss;

        public DSSWatcher(BlockingQueue<DssEvent> eventQueue, IDynamicStatusStoreService dss) {
            this.eventQueue = eventQueue;
            this.dss = dss;
        }

        public void startWatching() throws DynamicStatusStoreException {
            this.watchID = this.dss.watchPrefix(this, "run");
        }

        public void stopWatching() throws DynamicStatusStoreException {
            this.dss.unwatch(this.watchID);
        }

        @Override
        public void propertyModified(String key, Event event, String oldValue, String newValue) {
    
            if (event == null || key == null) {
                return;
            }
    
            Matcher matcher = runTestPattern.matcher(key);
            if (!matcher.find()) {
                return;
            }
    
            String runName = matcher.group(1);

            DssEvent dssEvent = new DssEvent(event, runName, oldValue, newValue);
    
            eventQueue.add(dssEvent);
        }
    }

    protected ResourceManagementRunWatch(
        IFramework framework,
        ArrayList<IResourceManagementProvider> resourceManagementProviders,
        ScheduledExecutorService scheduledExecutorService
    ) throws FrameworkException {

        logger.debug("ResourceManagementRunWatch: entered.");
        BlockingQueue<DssEvent> eventQueue = new LinkedBlockingQueue<DssEvent>();
        WatchEventProcessor processor = new WatchEventProcessor(eventQueue, resourceManagementProviders);
    
        scheduledExecutorService.scheduleWithFixedDelay(processor, 
				framework.getRandom().nextInt(20),
				10, 
				TimeUnit.SECONDS);

        IDynamicStatusStoreService dss = framework.getDynamicStatusStoreService("framework");

        DSSWatcher watcher = new DSSWatcher(eventQueue, dss);
        
        watcher.startWatching();
        logger.debug("ResourceManagementRunWatch: exiting.");
    }

   

    public void shutdown() {
        logger.debug("ResourceManagementRunWatch: shutdown() entered.");
        try {
            this.watcher.stopWatching();
        } catch (DynamicStatusStoreException e) {
        }
        logger.debug("ResourceManagementRunWatch: shutdown() exiting.");
    }

}
