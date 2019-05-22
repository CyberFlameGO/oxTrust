package org.gluu.oxtrust.api.server.api.impl;

import com.google.common.base.Preconditions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.gluu.oxtrust.api.server.model.AuthenticationMethod;
import org.gluu.oxtrust.api.server.util.ApiConstants;
import org.gluu.oxtrust.api.server.util.Constants;
import org.gluu.oxtrust.ldap.service.ConfigurationService;
import org.gluu.oxtrust.model.GluuConfiguration;
import org.gluu.oxtrust.service.filter.ProtectedApi;
import org.slf4j.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path(ApiConstants.BASE_API_URL + ApiConstants.ACRS)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class AuthenticationMethodWebResource extends BaseWebResource {

	@Inject
	private Logger logger;

	@Inject
	private ConfigurationService configurationService;

	public AuthenticationMethodWebResource() {
	}

    @GET
    @Operation(description = "Get current authentication methods")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", content = @Content(
                    schema = @Schema(implementation = AuthenticationMethod.class)
            ), description = Constants.RESULT_SUCCESS),
            @ApiResponse(responseCode = "500", description = "Server error")})
	@ProtectedApi(scopes = { READ_ACCESS })
	public Response getCurrentAuthentication() {
		log(logger, "Processing getCurrentAuthentication()");
		try {
			GluuConfiguration configuration = configurationService.getConfiguration();
			AuthenticationMethod method = new AuthenticationMethod();
			method.setDefaultAcr(configuration.getAuthenticationMode());
			method.setOxtrustAcr(configuration.getOxTrustAuthenticationMode());
			return Response.ok(method).build();
		} catch (Exception e) {
			log(logger, e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
		}
	}

	@PUT
	@Operation(description = "Update authentication methods")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AuthenticationMethod.class)), description = Constants.RESULT_SUCCESS),
            @ApiResponse(responseCode = "500", description = "Server error")})
	@ProtectedApi(scopes = { WRITE_ACCESS })
	public Response updateAuthenticationMethod(AuthenticationMethod method) {
		log(logger, "Processing updateAuthenticationMethod()");
		try {
			Preconditions.checkNotNull(method, "Attempt to update null method");
			GluuConfiguration configuration = configurationService.getConfiguration();
			if (method.getDefaultAcr() != null || method.getOxtrustAcr() != null) {
				configuration.setAuthenticationMode(method.getDefaultAcr());
				configuration.setOxTrustAuthenticationMode(method.getOxtrustAcr());
				configurationService.updateConfiguration(configuration);
				configuration = configurationService.getConfiguration();
				method.setDefaultAcr(configuration.getAuthenticationMode());
				method.setOxtrustAcr(configuration.getOxTrustAuthenticationMode());
				return Response.ok(method).build();
			} else {
				return Response.status(Response.Status.PRECONDITION_FAILED).build();
			}
		} catch (Exception e) {
			log(logger, e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
		}
	}

}
