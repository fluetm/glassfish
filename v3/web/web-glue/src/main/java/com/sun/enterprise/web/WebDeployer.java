/*
 * The contents of this file are subject to the terms 
 * of the Common Development and Distribution License 
 * (the License).  You may not use this file except in
 * compliance with the License.
 * 
 * You can obtain a copy of the license at 
 * https://glassfish.dev.java.net/public/CDDLv1.0.html or
 * glassfish/bootstrap/legal/CDDLv1.0.txt.
 * See the License for the specific language governing 
 * permissions and limitations under the License.
 * 
 * When distributing Covered Code, include this CDDL 
 * Header Notice in each file and include the License file 
 * at glassfish/bootstrap/legal/CDDLv1.0.txt.  
 * If applicable, add the following below the CDDL Header, 
 * with the fields enclosed by brackets [] replaced by
 * you own identifying information: 
 * "Portions Copyrighted [year] [name of copyright owner]"
 * 
 * Copyright 2008 Sun Microsystems, Inc. All rights reserved.
 */

package com.sun.enterprise.web;


import com.sun.enterprise.config.serverbeans.ConfigBeansUtilities;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.ServerTags;
import com.sun.enterprise.config.serverbeans.Module;
import com.sun.enterprise.deployment.Application;
import com.sun.enterprise.deployment.WebBundleDescriptor;
import org.glassfish.internal.api.ServerContext;
import org.glassfish.loader.util.ASClassLoaderUtil;
import org.glassfish.api.deployment.DeploymentContext;
import org.glassfish.api.deployment.MetaData;
import org.glassfish.api.deployment.OpsParams;
import org.glassfish.api.deployment.DeployCommandParameters;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.api.container.RequestDispatcher;
import org.glassfish.javaee.core.deployment.JavaEEDeployer;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Service;
import org.glassfish.web.jsp.JSPCompiler;
import org.glassfish.deployment.common.DeploymentException;
import org.glassfish.deployment.common.DeploymentProperties;

import java.util.*;
import java.util.logging.Level;
import java.io.File;

/**
 * Web module deployer.
 *
 * @author jluehe
 * @author Jerome Dochez
 */
@Service
public class WebDeployer extends JavaEEDeployer<WebContainer, WebApplication>{

    
    @Inject
    ServerContext sc;

    @Inject
    Domain domain;

    @Inject
    ServerEnvironment env;

    @Inject
    RequestDispatcher dispatcher;

    /**
     * Constructor
     */
    public WebDeployer() {
    }
    

    protected String getModuleType () {
        return "web";
    }

    /**
     * Returns the meta data assocated with this Deployer
     *
     * @return the meta data for this Deployer
     */
    public MetaData getMetaData() {

        return new MetaData(false, null,
                new Class[] { WebBundleDescriptor.class });
    }

    public <V> V loadMetaData(Class<V> type, DeploymentContext dc) {
        
        WebBundleDescriptor wbd = dc.getModuleMetaData(WebBundleDescriptor.class);

        if (wbd.isStandalone()) {
            // the context root should be set using the following precedence
            // for standalone web module
            // 1. User specified value through DeployCommand
            // 2. Context root value specified through sun-web.xml
            // 3. Context root from last deployment if applicable
            // 4. The default context root
            // 5. archive name
            DeployCommandParameters params = dc.getCommandParameters(DeployCommandParameters.class);
            String contextRoot = params.contextRoot;
            if(contextRoot==null) {
                contextRoot = wbd.getContextRoot();
                if("".equals(contextRoot))
                    contextRoot = null;
            }
            if(contextRoot==null) {
                contextRoot = params.previousContextRoot;
            }
            if(contextRoot==null)
                contextRoot = params.name();
            if(contextRoot==null)
                contextRoot = dc.getSource().getName();

            if (!contextRoot.startsWith("/")) {
                contextRoot = "/" + contextRoot;
            }
            wbd.setContextRoot(contextRoot);
            wbd.setName(params.name());

            // set the context root to deployment context props so this value
            // will be persisted in domain.xml
            dc.getProps().setProperty(ServerTags.CONTEXT_ROOT, contextRoot);
        } 

        return null;
    }

    private WebModuleConfig loadWebModuleConfig(DeploymentContext dc) {
        
        WebModuleConfig wmInfo = null;
        
        try {
            DeployCommandParameters params = dc.getCommandParameters(DeployCommandParameters.class);

            wmInfo = new WebModuleConfig();
            
            wmInfo.setDescriptor(dc.getModuleMetaData(WebBundleDescriptor.class));
            wmInfo.setVirtualServers(params.virtualservers);
            wmInfo.setLocation(dc.getSourceDir());
            wmInfo.setObjectType(dc.getProps().getProperty(ServerTags.OBJECT_TYPE));
        } catch (Exception ex) {
            dc.getLogger().log(Level.WARNING, "loadWebModuleConfig", ex);
        }
        
        return wmInfo;
        
    }
    
    @Override
    protected void generateArtifacts(DeploymentContext dc) 
        throws DeploymentException {
        DeployCommandParameters params = dc.getCommandParameters(DeployCommandParameters.class);
        if (params.precompilejsp) {
            //call JSPCompiler... 
            runJSPC(dc);
        }
    }

         
    @Override
    public WebApplication load(WebContainer container, DeploymentContext dc) {
        super.load(container, dc);
        WebModuleConfig wmInfo = loadWebModuleConfig(dc);
        com.sun.enterprise.config.serverbeans.Application config =
                domain.getApplications().getModule(com.sun.enterprise.config.serverbeans.Application.class,
                    dc.getCommandParameters(OpsParams.class).name());
        Module moduleConfig = null;
        if (config!=null) {
            moduleConfig = config.getModule(wmInfo.getDescriptor().getName());
        }
        WebApplication webApp = new WebApplication(container, wmInfo, moduleConfig,
            (Boolean.parseBoolean(dc.getProps().getProperty(DeploymentProperties.KEEP_SESSIONS))?
                dc.getProps():null));
        return webApp;
    }

    
    public void unload(WebApplication webApplication, DeploymentContext dc) {

        // dochez : quite a hack..
        // I have no choice but to do the following hack. The thing is that
        // WebApplication.stop() is saving the saving the sessions and it
        // does not have access to DeploymentContext
        // we will need a better solution after prelude.
        if (webApplication.props!=null) {
            if ((dc.getProps().get("ActionReportProperties"))!=null) {
                ((Properties) dc.getProps().get("ActionReportProperties")).putAll(webApplication.props);
            }
        }
    }
        
    /**
     * This method setups the in/outDir and classpath and invoke
     * JSPCompiler.
     * @param dc - DeploymentContext to get command parameters and
     *             source directory and compile jsp directory.
     * @throws DeploymentException if JSPCompiler is unsuccessful.
     */
    void runJSPC(final DeploymentContext dc) throws DeploymentException {
        final WebBundleDescriptor wbd = (WebBundleDescriptor)dc.getModuleMetaData(
              Application.class).getStandaloneBundleDescriptor();
        try {
            final File outDir = dc.getScratchDir(env.kCompileJspDirName);
            final File inDir  = dc.getSourceDir();
            boolean delegate = true;
            com.sun.enterprise.deployment.runtime.web.ClassLoader clBean =
                    wbd.getSunDescriptor().getClassLoader();
            if (clBean != null) {
                String value = clBean.getAttributeValue(
                    com.sun.enterprise.deployment.runtime.web.ClassLoader.DELEGATE);
                delegate = ConfigBeansUtilities.toBoolean(value);
            }

            StringBuilder classpath = new StringBuilder(
                super.getCommonClassPath());
            classpath.append(File.pathSeparatorChar);
            classpath.append(ASClassLoaderUtil.getModuleClassPath(
                    sc.getDefaultHabitat(),
                    wbd.getApplication().getName(), 
                    dc.getCommandParameters(
                        DeployCommandParameters.class).libraries)); 
            JSPCompiler.compile(inDir, outDir, wbd, classpath.toString(), sc);
        } catch (DeploymentException de) {
            dc.getLogger().log(Level.SEVERE, "Error compiling JSP", de);
            throw de;
        }
    }
}
