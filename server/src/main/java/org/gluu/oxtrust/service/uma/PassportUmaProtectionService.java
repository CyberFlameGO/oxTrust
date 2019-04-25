/*
 * oxTrust is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.gluu.oxtrust.service.uma;

import java.io.Serializable;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import org.gluu.config.oxtrust.AppConfiguration;
import org.gluu.oxtrust.ldap.service.ConfigurationService;
import org.gluu.oxtrust.model.GluuConfiguration;
import org.gluu.persist.model.base.GluuBoolean;
import org.slf4j.Logger;

/**
 * Provides service to protect Passport Passport Rest service endpoints
 * 
 * @author Yuriy Movchan Date: 012/06/2016
 */
@ApplicationScoped
@BindingUrls({"/passport/config"})
public class PassportUmaProtectionService extends BaseUmaProtectionService implements Serializable {

	private static final long serialVersionUID = -5547131971095468865L;

    @Inject
    private Logger log;

	@Inject
	private AppConfiguration appConfiguration;

	@Inject
	private ConfigurationService configurationService;

	protected String getClientId() {
		return appConfiguration.getPassportUmaClientId();
	}

	protected String getClientKeyStorePassword() {
		return appConfiguration.getPassportUmaClientKeyStorePassword();
	}

	protected String getClientKeyStoreFile() {
		return appConfiguration.getPassportUmaClientKeyStoreFile();
	}

	protected String getClientKeyId() {
		return appConfiguration.getPassportUmaClientKeyId();
	}

	public String getUmaResourceId() {
		return appConfiguration.getPassportUmaResourceId();
	}

	public String getUmaScope() {
		return appConfiguration.getPassportUmaScope();
	}

	public boolean isEnabled() {
		return isPassportEnabled() && isEnabledUmaAuthentication();
	}

	private boolean isPassportEnabled() {
		GluuConfiguration configuration = configurationService.getConfiguration();
		return configuration.isPassportEnabled();
	}

    public Response processAuthorization(HttpHeaders headers, ResourceInfo resourceInfo){
        if (isEnabled()) {
            try {
                return processUmaAuthorization(headers.getHeaderString("Authorization"), resourceInfo);
            }
            catch (Exception e){
                log.error(e.getMessage(), e);
                return getErrorResponse(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
            }
        }
        else{
            log.info("UMA passport authentication is disabled");
            return getErrorResponse(Response.Status.SERVICE_UNAVAILABLE, "Passport configuration was disabled");
        }

    }

}
