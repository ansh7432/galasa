/*
 * Copyright contributors to the Galasa project
 */
package dev.galasa.zossecurity.internal.resourcemanagement;

import static dev.galasa.zossecurity.internal.ZosSecurityImpl.ZOS_KERBEROS_PRINCIPAL_PATTERN;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import dev.galasa.framework.spi.IDynamicStatusStoreService;
import dev.galasa.framework.spi.IFramework;
import dev.galasa.framework.spi.IResourceManagement;
import dev.galasa.zossecurity.ZosSecurityManagerException;
import dev.galasa.zossecurity.internal.ZosSecurityImpl;
import dev.galasa.zossecurity.internal.ZosSecurityImpl.ResourceType;
import dev.galasa.zossecurity.internal.resources.ZosKerberosPrincipalImpl;

public class ZosKerberosPrincipalResourceManagement implements Runnable {

	private final ZosSecurityImpl zosSecurity;
    private final IFramework framework;
    private final IResourceManagement resourceManagement;
    private final IDynamicStatusStoreService dss;
    private final Log logger = LogFactory.getLog(this.getClass());

    public ZosKerberosPrincipalResourceManagement(ZosSecurityImpl zosSecurtityImpl, IFramework framework, IResourceManagement resourceManagement, IDynamicStatusStoreService dss) {
        this.zosSecurity = zosSecurtityImpl;
    	this.framework = framework;
        this.resourceManagement = resourceManagement;
        this.dss = dss;
        this.logger.info("zOS Kerberos Principal resource management initialised");
    }

	@Override
    public void run() {
        logger.info("Starting zOS Kerberos Principal cleanup");
        try {
            // Find all the runs with zOS Kerberos Principals
            Map<String, String> zosKerberosPrincipals = dss.getPrefix(ResourceType.ZOS_KERBEROS_PRINCIPAL.getName() + ".run.");

            Set<String> activeRunNames = this.framework.getFrameworkRuns().getActiveRunNames();

            for (String key : zosKerberosPrincipals.keySet()) {
                Matcher matcher = ZOS_KERBEROS_PRINCIPAL_PATTERN.matcher(key);
                if (matcher.find()) {
                    String runName = matcher.group(1);

                    if (!activeRunNames.contains(runName)) {
                        String kerberosPrincipal = matcher.group(2);
                        String sysplexId = matcher.group(3);

                        if (!activeRunNames.contains(runName)) {
                        	logger.info("Discarding zOS Kerberos Principal " + kerberosPrincipal + " on sysplex " + sysplexId + " as run " + runName + " has gone");

                        	try {
                        		ZosKerberosPrincipalImpl zosKerberosPrincipal = new ZosKerberosPrincipalImpl(zosSecurity, kerberosPrincipal, sysplexId, runName);
                        		zosKerberosPrincipal.delete();
                        	} catch (ZosSecurityManagerException e) {
                        		logger.error("Failed to discard zOS Kerberos Principal " + kerberosPrincipal + " for run " + runName + " - " + e.getCause());
                        	}
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failure during zOS Kerberos Principal cleanup", e);
        }

        this.resourceManagement.resourceManagementRunSuccessful();
        logger.info("Finished zOS Kerberos Principal cleanup");
    }

    public void runFinishedOrDeleted(String runName) {
        try {
            Map<String, String> serverRuns = dss.getPrefix(ResourceType.ZOS_KERBEROS_PRINCIPAL.getName() + ".run." + runName + ".");
            for (String key : serverRuns.keySet()) {
                Matcher matcher = ZOS_KERBEROS_PRINCIPAL_PATTERN.matcher(key);
                if (matcher.find()) {
                	String kerberosPrincipal = matcher.group(2);
                    String sysplexId = matcher.group(3);

                    logger.info("Discarding zOS Kerberos Principal " + kerberosPrincipal + " on sysplex " + sysplexId + " as run " + runName + " has gone");
                    
                    try {
                		ZosKerberosPrincipalImpl zosKerberosPrincipal = new ZosKerberosPrincipalImpl(zosSecurity, kerberosPrincipal, sysplexId, runName);
                		zosKerberosPrincipal.delete();
                	} catch (ZosSecurityManagerException e) {
                		logger.error("Failed to discard zOS Kerberos Principal " + kerberosPrincipal + " for run " + runName + " - " + e.getCause());
                	}
                }
            }
        } catch (Exception e) {
            logger.error("Failed to delete zOS Kerberos Principal for run " + runName + " - " + e.getCause());
        }
    }
}
