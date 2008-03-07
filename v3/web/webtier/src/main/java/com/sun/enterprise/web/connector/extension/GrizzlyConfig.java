/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package com.sun.enterprise.web.connector.extension;

import javax.management.j2ee.statistics.Stats;

import com.sun.enterprise.admin.monitor.registry.MonitoringRegistry;
import com.sun.enterprise.admin.monitor.registry.MonitoringRegistrationException;
import com.sun.enterprise.admin.monitor.registry.MonitoringLevel;
import com.sun.enterprise.admin.monitor.registry.MonitoringLevelListener;
import com.sun.enterprise.admin.monitor.registry.MonitoredObjectType;
import com.sun.enterprise.config.serverbeans.ModuleMonitoringLevels;

import com.sun.enterprise.config.serverbeans.Config;
import com.sun.enterprise.web.WebContainer;
import com.sun.logging.LogDomains;

import java.util.ArrayList;
import java.util.logging.Logger;
import java.util.logging.Level;
import javax.management.ObjectName;
import javax.management.MBeanServerFactory;
import javax.management.MBeanServer;

/**
 * This class track monitoring or Grizzly, using JMX to invoke Grizzly main
 * classes.
 *
 * @author Jeanfrancois Arcand 
 */ 
public class GrizzlyConfig implements MonitoringLevelListener{
    private final static Logger logger
        = LogDomains.getLogger(LogDomains.WEB_LOGGER);
    
    /**
     * The mbean server used to lookup Grizzly.
     */
    private MBeanServer mBeanServer;


    /**
     * Is monitoring already started.
     */
    private boolean isMonitoringEnabled = false;
    
    /**
     * The JMX domain
     */
    private String domain;
    

    /**
     * The port used to lookup Grizzly's Selector
     */
    private int port;


    /**
     * The list of instance created. This list is not thread-safe.
     */
    private static ArrayList<GrizzlyConfig>
            grizzlyConfigList = new ArrayList<GrizzlyConfig>();
    

    // --------------------------------------------------------------- //
   
    
    /**
     * Creates the monitoring helper.
     */
    public GrizzlyConfig(String domain, int port){
        this.domain = domain;
        this.port = port;
        
        // get an instance of the MBeanServer
        ArrayList servers = MBeanServerFactory.findMBeanServer(null);
        if(!servers.isEmpty())
            mBeanServer = (MBeanServer)servers.get(0);
        else
            mBeanServer = MBeanServerFactory.createMBeanServer();
        
        grizzlyConfigList.add(this);
    }
    
    
    public void initConfig(){
        initMonitoringLevel();
    }
    
    
    private void initMonitoringLevel() {
        try{
            Config cfg = com.sun.enterprise.v3.server.Globals.getDefaultHabitat().getComponent(Config.class);
            
            MonitoringLevel monitoringLevel = MonitoringLevel.OFF; // default per DTD

            if (cfg.getMonitoringService() != null) {
                ModuleMonitoringLevels levels =
                    cfg.getMonitoringService().getModuleMonitoringLevels();
                if (levels != null) {
                    monitoringLevel = MonitoringLevel.instance(
                                                    levels.getHttpService());
                }
            }
        
            if(MonitoringLevel.OFF.equals(monitoringLevel)) {
                isMonitoringEnabled = false;
            } else {
                isMonitoringEnabled = true;
            } 
            
            String methodToInvoke = isMonitoringEnabled ? "enableMonitoring" :
                "disableMonitoring";
            invokeGrizzly(methodToInvoke);
        } catch (Exception ex) {
            logger.log(Level.WARNING,
                "selectorThread.initMonitoringException",
                 new Object[]{new Integer(port),ex});
        }
    } 
    
    
    public void registerMonitoringLevelEvents() {
        if (WebContainer.getInstance()==null) {
            return;
        }
        MonitoringRegistry monitoringRegistry = 
            WebContainer.getInstance().getServerContext().getDefaultHabitat().getComponent(MonitoringRegistry.class);
        if (monitoringRegistry!=null) {
            monitoringRegistry.registerMonitoringLevelListener(
                this, MonitoredObjectType.HTTP_LISTENER);
        }
    }

    
    public void unregisterMonitoringLevelEvents() {
        if (WebContainer.getInstance()==null) {
            return;
        }
        MonitoringRegistry monitoringRegistry =
            WebContainer.getInstance().getServerContext().getDefaultHabitat().getComponent(MonitoringRegistry.class);
        if (monitoringRegistry!=null) {
            monitoringRegistry.unregisterMonitoringLevelListener(this);
        }
    }

    
    public void setLevel(MonitoringLevel level) {
        // deprecated, ignore
    }
    
    
    public void changeLevel(MonitoringLevel from, MonitoringLevel to,
                            MonitoredObjectType type) {
        if (MonitoredObjectType.HTTP_LISTENER.equals(type)) {
            if(MonitoringLevel.OFF.equals(to)) {
                isMonitoringEnabled = false;
            } else {
                isMonitoringEnabled = true;
            }
        }            
        String methodToInvoke = isMonitoringEnabled ? "enableMonitoring" :
            "disabledMonitoring";
        invokeGrizzly(methodToInvoke);        
    }
    
    
    public void changeLevel(MonitoringLevel from, MonitoringLevel to, 
			    Stats handback) {
        // deprecated, ignore
    }

    
    protected final void invokeGrizzly(String methodToInvoke) {  
        invokeGrizzly(methodToInvoke,null,null);
    }   
     
    
    protected final void invokeGrizzly(String methodToInvoke, 
                                       Object[] objects, String[] signature) {  
        try{
            String onStr = domain + ":type=Selector,name=http" + port;
            ObjectName objectName = new ObjectName(onStr);
            mBeanServer.invoke(objectName,methodToInvoke,objects,signature);
        } catch ( Exception ex ){
            logger.log(Level.SEVERE, "Exception while invoking mebean server operation " + methodToInvoke, ex.getMessage());
            //throw new RuntimeException(ex);
        }
    }

    
    /**
     * Enable CallFlow gathering mechanism.
     */
    public final void setEnableCallFlow(boolean enableCallFlow){
        String methodToInvoke = enableCallFlow ? "enableMonitoring" :
            "disabledMonitoring";
        invokeGrizzly(methodToInvoke);        
    }

    
    /**
     * Return the list of all instance of this class.
     */
    public static ArrayList<GrizzlyConfig> getGrizzlyConfigInstances(){
        return grizzlyConfigList;
    }
    
    
    /**
     * Return the port this configuration belongs.
     */
    public int getPort(){
        return port;
    }
}
