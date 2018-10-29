/*
 * oxTrust is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.gluu.oxtrust.action;

import java.io.IOException;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.enterprise.context.ConversationScoped;
import javax.faces.application.FacesMessage;
import javax.faces.context.FacesContext;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.gluu.jsf2.message.FacesMessages;
import org.gluu.jsf2.service.ConversationService;
import org.gluu.oxtrust.ldap.service.AttributeService;
import org.gluu.oxtrust.ldap.service.ClientService;
import org.gluu.oxtrust.ldap.service.EncryptionService;
import org.gluu.oxtrust.ldap.service.OxTrustAuditService;
import org.gluu.oxtrust.ldap.service.ScopeService;
import org.gluu.oxtrust.model.GluuGroup;
import org.gluu.oxtrust.model.OxAuthClient;
import org.gluu.oxtrust.model.OxAuthScope;
import org.gluu.oxtrust.security.Identity;
import org.gluu.oxtrust.util.OxTrustConstants;
import org.gluu.site.ldap.persistence.exception.LdapMappingException;
import org.slf4j.Logger;
import org.xdi.config.oxtrust.AppConfiguration;
import org.xdi.model.DisplayNameEntry;
import org.xdi.model.GluuAttribute;
import org.xdi.model.SelectableEntity;
import org.xdi.model.custom.script.CustomScriptType;
import org.xdi.model.custom.script.model.CustomScript;
import org.xdi.oxauth.model.common.GrantType;
import org.xdi.oxauth.model.common.ResponseType;
import org.xdi.oxauth.model.util.URLPatternList;
import org.xdi.service.LookupService;
import org.xdi.service.custom.script.AbstractCustomScriptService;
import org.xdi.service.security.Secure;
import org.xdi.util.StringHelper;
import org.xdi.util.Util;
import org.xdi.util.security.StringEncrypter.EncryptionException;

import net.steppschuh.markdowngenerator.list.UnorderedList;
import net.steppschuh.markdowngenerator.text.heading.Heading;

/**
 * Action class for viewing and updating clients.
 *
 * @author Reda Zerrad Date: 06.11.2012
 * @author Yuriy Movchan Date: 04/07/2014
 * @author Javier Rojas Blum
 * @version June 21, 2018
 */
@Named
@ConversationScoped
@Secure("#{permissionService.hasPermission('client', 'access')}")
public class UpdateClientAction implements Serializable {

	private static final long serialVersionUID = -5756470620039988876L;

	@Inject
	private Logger log;

	@Inject
	private ClientService clientService;

	@Inject
	private ScopeService scopeService;

	@Inject
	private AttributeService attributeService;

	@Inject
	private AbstractCustomScriptService customScriptService;

	@Inject
	private LookupService lookupService;

	@Inject
	private FacesMessages facesMessages;

	@Inject
	private ConversationService conversationService;

	@Inject
	private EncryptionService encryptionService;

	@Inject
	private AppConfiguration appConfiguration;

	@Inject
	private OxTrustAuditService oxTrustAuditService;

	@Inject
	private Identity identity;

	private String inum;

	private String markDown = "";

	private boolean update;

	private Date previousClientExpirationDate;

	private OxAuthClient client;

	private List<String> loginUris;
	private List<String> logoutUris;
	private List<String> clientlogoutUris;
	private List<String> claimRedirectURIList;

	private List<OxAuthScope> scopes;
	private List<DisplayNameEntry> claims;
	private List<ResponseType> responseTypes;
	private List<CustomScript> customScripts;
	private List<GrantType> grantTypes;
	private List<String> contacts;
	private List<String> requestUris;
	private List<String> authorizedOrigins;

	private String searchAvailableClaimPattern;
	private String oldSearchAvailableClaimPattern;

	private String availableLoginUri = "https://";
	private String availableLogoutUri = "https://";
	private String availableClientlogoutUri = "https://";
	private String availableContact = "";
	private String availableRequestUri = "https://";
	private String availableAuthorizedOrigin = "https://";
	private String availableClaimRedirectUri = "https://";

	public String getAvailableAuthorizedOrigin() {
		return availableAuthorizedOrigin;
	}

	public void setAvailableAuthorizedOrigin(String availableAuthorizedOrigin) {
		this.availableAuthorizedOrigin = availableAuthorizedOrigin;
	}

	public String getAvailableClaimRedirectUri() {
		return availableClaimRedirectUri;
	}

	public void setAvailableClaimRedirectUri(String availableClaimRedirectUri) {
		this.availableClaimRedirectUri = availableClaimRedirectUri;
	}

	private List<GluuAttribute> availableClaims;
	private List<GluuGroup> availableGroups;
	private List<SelectableEntity<ResponseType>> availableResponseTypes;
	private List<SelectableEntity<CustomScript>> availableCustomScripts;
	private List<SelectableEntity<GrantType>> availableGrantTypes;
	private List<SelectableEntity<OxAuthScope>> availableScopes;

	public String add() throws Exception {
		if (this.client != null) {
			return OxTrustConstants.RESULT_SUCCESS;
		}

		this.update = false;
		this.client = new OxAuthClient();

		try {
			this.loginUris = getNonEmptyStringList(client.getOxAuthRedirectURIs());
			this.logoutUris = getNonEmptyStringList(client.getOxAuthPostLogoutRedirectURIs());
			this.clientlogoutUris = getNonEmptyStringList(client.getLogoutUri());
			this.scopes = getInitialEntries();
			this.claims = getInitialClaimDisplayNameEntries();
			this.responseTypes = getInitialResponseTypes();
			this.grantTypes = getInitialGrantTypes();
			this.contacts = getNonEmptyStringList(client.getContacts());
			this.requestUris = getNonEmptyStringList(client.getRequestUris());
			this.authorizedOrigins = getNonEmptyStringList(client.getAuthorizedOrigins());
			this.claimRedirectURIList = getNonEmptyStringList(client.getClaimRedirectURI());
			this.customScripts = getInitialAcrs();
		} catch (LdapMappingException ex) {
			log.error("Failed to prepare lists", ex);

			facesMessages.add(FacesMessage.SEVERITY_ERROR, "Failed to add new client");
			conversationService.endConversation();

			return OxTrustConstants.RESULT_FAILURE;
		}

		return OxTrustConstants.RESULT_SUCCESS;
	}

	private List<CustomScript> getInitialAcrs() {
		this.customScripts = new ArrayList<CustomScript>();
		if (this.client.getDefaultAcrValues() != null && this.client.getDefaultAcrValues().length >= 1) {
			for (String scriptName : this.client.getDefaultAcrValues()) {
				CustomScript customScript = new CustomScript();
				customScript.setName(scriptName);
				this.customScripts.add(customScript);
			}
		}
		return this.customScripts;
	}

	private List<OxAuthScope> getInitialEntries() {
		List<OxAuthScope> existingScopes = new ArrayList<OxAuthScope>();
		if ((client.getOxAuthScopes() == null) || (client.getOxAuthScopes().size() == 0)) {
			return existingScopes;
		}
		for (String dn : client.getOxAuthScopes()) {
			String dnString = dn.split(",")[0];
			String inum = dnString.split("=")[1];
			try {
				existingScopes.add(scopeService.getScopeByInum(inum));
			} catch (Exception e) {
			}
		}
		return existingScopes;
	}

	public String update() throws Exception {
		if (this.client != null) {
			return OxTrustConstants.RESULT_SUCCESS;
		}

		this.update = true;
		log.debug("this.update : " + this.update);
		try {
			log.debug("inum : " + inum);
			this.client = clientService.getClientByInum(inum);
			previousClientExpirationDate = this.client.getClientSecretExpiresAt();
		} catch (LdapMappingException ex) {
			log.error("Failed to find client {}", inum, ex);
		}

		if (this.client == null) {
			log.error("Failed to load client {}", inum);
			facesMessages.add(FacesMessage.SEVERITY_ERROR, "Failed to find client");

			conversationService.endConversation();

			return OxTrustConstants.RESULT_FAILURE;
		}

		try {
			this.loginUris = getNonEmptyStringList(client.getOxAuthRedirectURIs());
			this.logoutUris = getNonEmptyStringList(client.getOxAuthPostLogoutRedirectURIs());
			this.clientlogoutUris = getNonEmptyStringList(client.getLogoutUri());
			this.scopes = getInitialEntries();
			this.claims = getInitialClaimDisplayNameEntries();
			this.responseTypes = getInitialResponseTypes();
			this.grantTypes = getInitialGrantTypes();
			this.contacts = getNonEmptyStringList(client.getContacts());
			this.requestUris = getNonEmptyStringList(client.getRequestUris());
			this.authorizedOrigins = getNonEmptyStringList(client.getAuthorizedOrigins());
			this.claimRedirectURIList = getNonEmptyStringList(client.getClaimRedirectURI());
			this.customScripts = getInitialAcrs();
		} catch (LdapMappingException ex) {
			log.error("Failed to prepare lists", ex);
			facesMessages.add(FacesMessage.SEVERITY_ERROR, "Failed to load client");

			conversationService.endConversation();

			return OxTrustConstants.RESULT_FAILURE;
		}

		return OxTrustConstants.RESULT_SUCCESS;
	}

	private List<String> getNonEmptyStringList(List<String> currentList) {
		if (currentList != null && currentList.size() > 0) {
			return new ArrayList<String>(currentList);
		} else {
			return new ArrayList<String>();
		}
	}

	private List<String> getNonEmptyStringList(String[] currentList) {
		if (currentList != null && currentList.length > 0) {
			return new ArrayList<String>(Arrays.asList(currentList));
		} else {
			return new ArrayList<String>();
		}
	}

	public String cancel() {
		if (update) {
			facesMessages.add(FacesMessage.SEVERITY_INFO,
					"Client '#{updateClientAction.client.displayName}' not updated");
		} else {
			facesMessages.add(FacesMessage.SEVERITY_INFO, "New client not added");
		}

		conversationService.endConversation();

		return OxTrustConstants.RESULT_SUCCESS;
	}

	public String save() throws Exception {
		LocalDate localDate = LocalDate.now();
		LocalDate nextCentury = localDate.plusYears(100);
		Date nextCenturyDate = Date.from(nextCentury.atStartOfDay(ZoneId.systemDefault()).toInstant());
		if (this.client.getClientSecretExpiresAt() != null && this.client.getClientSecretExpiresAt()
				.before(Date.from(LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()))) {
			this.client.setClientSecretExpiresAt(nextCenturyDate);
		}
		if (previousClientExpirationDate != null && this.client.getClientSecretExpiresAt().before(new Date())) {
			facesMessages.add(FacesMessage.SEVERITY_ERROR,
					"This client has expired. Update the expiration date in order to save changes");
			return OxTrustConstants.RESULT_FAILURE;
		}

		updateLoginURIs();
		updateLogoutURIs();
		updateClientLogoutURIs();
		updateScopes();
		updateClaims();
		updateResponseTypes();
		updateCustomScripts();
		updateGrantTypes();
		updateContacts();
		updateRequestUris();
		updateAuthorizedOrigins();
		updateClaimredirectUri();
		// Trim all URI properties
		trimUriProperties();
		this.client.setEncodedClientSecret(encryptionService.encrypt(this.client.getOxAuthClientSecret()));
		if (update) {
			// Update client
			try {
				clientService.updateClient(this.client);
				oxTrustAuditService.audit(
						"OPENID CLIENT " + this.client.getInum() + " **" + this.client.getDisplayName() + "** UPDATED",
						identity.getUser(),
						(HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest());
			} catch (LdapMappingException ex) {
				log.error("Failed to update client {}", this.inum, ex);
				facesMessages.add(FacesMessage.SEVERITY_ERROR,
						"Failed to update client '#{updateClientAction.client.displayName}'");
				return OxTrustConstants.RESULT_FAILURE;
			}

			facesMessages.add(FacesMessage.SEVERITY_INFO,
					"Client '#{updateClientAction.client.displayName}' updated successfully");
		} else {
			this.inum = clientService.generateInumForNewClient();
			String dn = clientService.getDnForClient(this.inum);

			if (StringHelper.isEmpty(this.client.getEncodedClientSecret())) {
				generatePassword();
			}

			// Save client
			this.client.setDn(dn);
			this.client.setInum(this.inum);
			try {
				clientService.addClient(this.client);
				oxTrustAuditService.audit(
						"OPENID CLIENT " + this.client.getInum() + " **" + this.client.getDisplayName() + "** ADDED ",
						identity.getUser(),
						(HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest());
			} catch (LdapMappingException ex) {
				log.error("Failed to add new client {}", this.inum, ex);
				facesMessages.add(FacesMessage.SEVERITY_ERROR, "Failed to add new client");
				return OxTrustConstants.RESULT_FAILURE;
			}

			facesMessages.add(FacesMessage.SEVERITY_INFO,
					"New client '#{updateClientAction.client.displayName}' added successfully");
			conversationService.endConversation();
			this.update = true;
		}
		return OxTrustConstants.RESULT_SUCCESS;
	}

	private void trimUriProperties() {
		this.client.setClientUri(StringHelper.trimAll(this.client.getClientUri()));
		this.client.setJwksUri(StringHelper.trimAll(this.client.getJwksUri()));
		this.client.setLogoUri(StringHelper.trimAll(this.client.getLogoUri()));
		this.client.setPolicyUri(StringHelper.trimAll(this.client.getPolicyUri()));
		this.client.setSectorIdentifierUri(StringHelper.trimAll(this.client.getSectorIdentifierUri()));
		this.client.setTosUri(StringHelper.trimAll(this.client.getTosUri()));
		this.client.setInitiateLoginUri(StringHelper.trimAll(this.client.getInitiateLoginUri()));
	}

	public String delete() throws Exception {
		if (update) {
			// Remove client
			try {
				clientService.removeClient(this.client);
				oxTrustAuditService.audit(
						"OPENID CLIENT " + this.client.getInum() + " **" + this.client.getDisplayName() + "** DELETED ",
						identity.getUser(),
						(HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest());
				facesMessages.add(FacesMessage.SEVERITY_INFO,
						"Client '#{updateClientAction.client.displayName}' removed successfully");
				conversationService.endConversation();

				return OxTrustConstants.RESULT_SUCCESS;
			} catch (LdapMappingException ex) {
				log.error("Failed to remove client {}", this.inum, ex);
			}
		}

		facesMessages.add(FacesMessage.SEVERITY_ERROR,
				"Failed to remove client '#{updateClientAction.client.displayName}'");

		return OxTrustConstants.RESULT_FAILURE;
	}

	public void removeLoginURI(String uri) {
		removeFromList(this.loginUris, uri);
	}

	public void removeLogoutURI(String uri) {
		removeFromList(this.logoutUris, uri);
	}

	public void removeClientLogoutURI(String uri) {
		removeFromList(this.clientlogoutUris, uri);
	}

	public void removeClaimRedirectURI(String uri) {
		removeFromList(this.claimRedirectURIList, uri);
	}

	public void removeContact(String contact) {
		if (StringUtils.isEmpty(contact)) {
			return;
		}
		for (Iterator<String> iterator = contacts.iterator(); iterator.hasNext();) {
			String tmpContact = iterator.next();
			if (contact.equals(tmpContact)) {
				iterator.remove();
				break;
			}
		}
	}

	public void removeRequestUri(String requestUri) {
		if (StringUtils.isEmpty(requestUri)) {
			return;
		}

		for (Iterator<String> iterator = requestUris.iterator(); iterator.hasNext();) {
			String tmpRequestUri = iterator.next();
			if (requestUri.equals(tmpRequestUri)) {
				iterator.remove();
				break;
			}
		}
	}

	public void removeAuthorizedOrigin(String authorizedOrigin) {
		if (StringUtils.isEmpty(authorizedOrigin)) {
			return;
		}
		for (Iterator<String> iterator = authorizedOrigins.iterator(); iterator.hasNext();) {
			String tmpAuthorizationOrigin = iterator.next();
			if (authorizedOrigin.equals(tmpAuthorizationOrigin)) {
				iterator.remove();
				break;
			}
		}
	}

	private void removeFromList(List<String> uriList, String uri) {
		if (StringUtils.isEmpty(uri)) {
			return;
		}
		for (Iterator<String> iterator = uriList.iterator(); iterator.hasNext();) {
			String tmpUri = iterator.next();
			if (uri.equals(tmpUri)) {
				iterator.remove();
				break;
			}
		}
	}

	public void removeScope(String inum) {
		if (StringHelper.isEmpty(inum)) {
			return;
		}
		for (OxAuthScope scope : this.scopes) {
			if (scope.getInum().equalsIgnoreCase(inum)) {
				this.scopes.remove(scope);
				break;
			}
		}
	}

	private void addClaim(GluuAttribute claim) {
		DisplayNameEntry oneClaim = new DisplayNameEntry(claim.getDn(), claim.getInum(), claim.getDisplayName());
		this.claims.add(oneClaim);
	}

	public void removeClaim(String inum) throws Exception {
		if (StringHelper.isEmpty(inum)) {
			return;
		}
		String removeClaimDn = attributeService.getDnForAttribute(inum);
		for (Iterator<DisplayNameEntry> iterator = this.claims.iterator(); iterator.hasNext();) {
			DisplayNameEntry oneClaim = iterator.next();
			if (removeClaimDn.equals(oneClaim.getDn())) {
				iterator.remove();
				break;
			}
		}
	}

	public void acceptSelectLoginUri() {
		if (StringHelper.isEmpty(this.availableLoginUri)) {
			return;
		}

		if (!this.loginUris.contains(this.availableLoginUri) && checkWhiteListRedirectUris(availableLoginUri)
				&& checkBlackListRedirectUris(availableLoginUri)) {
			if (isAcceptable(this.availableLoginUri)) {
				this.loginUris.add(this.availableLoginUri);
			} else {
				facesMessages.add(FacesMessage.SEVERITY_ERROR, "A sector identifier must be defined first.",
						"A sector identifier must be defined first.");
			}

		} else {
			facesMessages.add(FacesMessage.SEVERITY_ERROR, "The URL is not valid or may be Blacklisted.",
					"The URL is not valid or may be Blacklisted.");
		}

		this.availableLoginUri = "https://";
	}

	private boolean isAcceptable(String availableLoginUri) {
		boolean result = false;
		try {
			if (this.loginUris.size() < 1) {
				result = true;
			} else if (this.loginUris.size() >= 1 && hasSameHostname(this.availableLoginUri)) {
				result = true;
			} else if (this.loginUris.size() >= 1 && !hasSameHostname(this.availableLoginUri) && sectorExist()) {
				result = true;
			}
		} catch (MalformedURLException e) {
			facesMessages.add(FacesMessage.SEVERITY_ERROR, "One of the url is no malformed",
					"One of the url is no malformed");
			log.error(e.getMessage());
		}
		return result;
	}

	private boolean hasSameHostname(String url1) throws MalformedURLException {
		boolean result = true;
		URL uri1 = new URL(url1);
		for (String url : this.loginUris) {
			URL uri = new URL(url);
			if (!(uri1.getHost().equalsIgnoreCase(uri.getHost()))) {
				result = false;
				break;
			}
		}
		return result;
	}

	private boolean sectorExist() {
		boolean result = false;
		String sectorUri = this.client.getSectorIdentifierUri();
		try {
			if (sectorUri != null && !sectorUri.isEmpty()) {
				JSONArray json = new JSONArray(IOUtils.toString(new URL(sectorUri), Charset.forName("UTF-8")));
				if (json != null) {
					result = true;
				}
			}
		} catch (MalformedURLException e) {
			facesMessages.add(FacesMessage.SEVERITY_ERROR, "The url of the sector assigned to this client is malformed",
					"The url of the sector assigned to this client is malformed");
			log.error(e.getMessage());
		} catch (IOException e) {
			log.error(e.getMessage());
		} catch (JSONException e) {
			log.error(e.getMessage());
		}
		return result;
	}

	public void acceptSelectClaims() {
		if (this.availableClaims == null) {
			return;
		}

		Set<String> addedClaimInums = new HashSet<String>();
		for (DisplayNameEntry claim : claims) {
			addedClaimInums.add(claim.getInum());
		}

		for (GluuAttribute aClaim : this.availableClaims) {
			if (aClaim.isSelected() && !addedClaimInums.contains(aClaim.getInum())) {
				addClaim(aClaim);
			}
		}
		this.searchAvailableClaimPattern = "";
	}

	public void acceptSelectLogoutUri() {
		if (StringHelper.isEmpty(this.availableLogoutUri)) {
			return;
		}
		if (!this.logoutUris.contains(this.availableLogoutUri) && checkWhiteListRedirectUris(availableLogoutUri)
				&& checkBlackListRedirectUris(availableLogoutUri)) {
			this.logoutUris.add(this.availableLogoutUri);
		} else {
			facesMessages.add(FacesMessage.SEVERITY_ERROR, "The URL is not valid or may be Blacklisted.");
		}
		this.availableLogoutUri = "https://";
	}

	public void acceptSelectClientLogoutUri() {
		if (StringHelper.isEmpty(this.availableClientlogoutUri)) {
			return;
		}
		if (!this.clientlogoutUris.contains(this.availableClientlogoutUri)) {
			this.clientlogoutUris.add(this.availableClientlogoutUri);
		}
		this.availableClientlogoutUri = "https://";
	}

	public void acceptSelectClaimRedirectUri() {
		if (StringHelper.isEmpty(this.availableClaimRedirectUri)) {
			return;
		}
		if (!this.claimRedirectURIList.contains(this.availableClaimRedirectUri)) {
			this.claimRedirectURIList.add(this.availableClaimRedirectUri);
		}
		this.availableClaimRedirectUri = "https://";
	}

	public void acceptSelectContact() {
		if (StringHelper.isEmpty(this.availableContact)) {
			return;
		}
		if (!contacts.contains((availableContact))) {
			contacts.add(availableContact);
		}
		this.availableContact = "";
	}

	public void acceptSelectRequestUri() {
		if (StringHelper.isEmpty(this.availableRequestUri)) {
			return;
		}
		if (!this.requestUris.contains(this.availableRequestUri)) {
			this.requestUris.add(this.availableRequestUri);
		}
		this.availableRequestUri = "https://";
	}

	public void acceptSelectAuthorizedOrigin() {
		if (StringHelper.isEmpty(this.availableAuthorizedOrigin)) {
			return;
		}
		if (!this.authorizedOrigins.contains(this.availableAuthorizedOrigin)) {
			this.authorizedOrigins.add(this.availableAuthorizedOrigin);
		}
		this.availableAuthorizedOrigin = "https://";
	}

	public void cancelSelectClaims() {
	}

	public void cancelSelectGroups() {
	}

	public void cancelSelectLoginUri() {
		this.availableLoginUri = "http://";
	}

	public void cancelSelectLogoutUri() {
		this.availableLogoutUri = "http://";
	}

	public void cancelClientLogoutUri() {
		this.availableClientlogoutUri = "http://";
	}

	public void cancelClaimRedirectUri() {
		this.availableClaimRedirectUri = "http://";
	}

	public void cancelSelectContact() {
		this.availableContact = "";
	}

	public void cancelSelectDefaultAcrValue() {
	}

	public void cancelSelectRequestUri() {
	}

	public void cancelSelectAuthorizedOrigin() {
	}

	private void updateLoginURIs() {
		if (this.loginUris == null || this.loginUris.size() == 0) {
			this.client.setOxAuthRedirectURIs(null);
			return;
		}

		List<String> tmpUris = new ArrayList<String>();
		for (String uri : this.loginUris) {
			tmpUris.add(StringHelper.trimAll(uri));
		}

		this.client.setOxAuthRedirectURIs(tmpUris);
	}

	private void updateLogoutURIs() {
		if (this.logoutUris == null || this.logoutUris.size() == 0) {
			this.client.setOxAuthPostLogoutRedirectURIs(null);
			return;
		}

		List<String> tmpUris = new ArrayList<String>();
		for (String uri : this.logoutUris) {
			tmpUris.add(StringHelper.trimAll(uri));
		}

		this.client.setOxAuthPostLogoutRedirectURIs(tmpUris);

	}

	private void updateClientLogoutURIs() {
		if (this.clientlogoutUris == null || this.clientlogoutUris.size() == 0) {
			this.client.setLogoutUri(null);
			return;
		}

		List<String> tmpUris = new ArrayList<String>();
		for (String uri : this.clientlogoutUris) {
			tmpUris.add(StringHelper.trimAll(uri));
		}

		this.client.setLogoutUri(tmpUris);

	}

	private void updateContacts() {
		validateContacts();
		if (contacts == null || contacts.size() == 0) {
			client.setContacts(null);
			return;
		}

		List<String> tmpContacts = new ArrayList<String>();
		for (String contact : contacts) {
			tmpContacts.add(contact);
		}

		client.setContacts(tmpContacts);
	}

	private void updateRequestUris() {
		if (requestUris == null || requestUris.size() == 0) {
			client.setRequestUris(null);
			return;
		}

		List<String> tmpRequestUris = new ArrayList<String>();
		for (String requestUri : requestUris) {
			tmpRequestUris.add(StringHelper.trimAll(requestUri));
		}

		client.setRequestUris(tmpRequestUris.toArray(new String[tmpRequestUris.size()]));
	}

	private void updateAuthorizedOrigins() {
		if (authorizedOrigins == null || authorizedOrigins.size() == 0) {
			client.setAuthorizedOrigins(null);
			return;
		}

		List<String> tmpAuthorizedOrigins = new ArrayList<String>();
		for (String authorizedOrigin : authorizedOrigins) {
			tmpAuthorizedOrigins.add(StringHelper.trimAll(authorizedOrigin));
		}

		client.setAuthorizedOrigins(tmpAuthorizedOrigins.toArray(new String[tmpAuthorizedOrigins.size()]));
	}

	private void updateClaimredirectUri() {
		if (claimRedirectURIList == null || claimRedirectURIList.size() == 0) {
			client.setClaimRedirectURI(null);
			return;
		}

		List<String> tmpClaimRedirectURI = new ArrayList<String>();
		for (String claimRedirectURI : claimRedirectURIList) {
			tmpClaimRedirectURI.add(StringHelper.trimAll(claimRedirectURI));
		}

		client.setClaimRedirectURI(tmpClaimRedirectURI.toArray(new String[tmpClaimRedirectURI.size()]));
	}

	private void updateClaims() {
		if (this.claims == null || this.claims.size() == 0) {
			this.client.setOxAuthClaims(null);
			return;
		}

		List<String> tmpClaims = new ArrayList<String>();
		for (DisplayNameEntry claim : this.claims) {
			tmpClaims.add(claim.getDn());
		}

		this.client.setOxAuthClaims(tmpClaims);
	}

	private void updateResponseTypes() {
		List<ResponseType> currentResponseTypes = this.responseTypes;
		if (currentResponseTypes == null || currentResponseTypes.size() == 0) {
			this.client.setResponseTypes(null);
			return;
		}
		this.client.setResponseTypes(currentResponseTypes.toArray(new ResponseType[currentResponseTypes.size()]));
	}

	private void updateScopes() {
		List<OxAuthScope> currentResponseTypes = this.scopes;
		if (currentResponseTypes == null || currentResponseTypes.size() == 0) {
			this.client.setOxAuthScopes(null);
			return;
		}
		List<String> scopes = new ArrayList<String>();
		for (OxAuthScope scope : this.scopes) {
			scopes.add(scope.getBaseDn());
		}
		this.client.setOxAuthScopes(scopes);
	}

	private void updateGrantTypes() {
		List<GrantType> currentGrantTypes = this.grantTypes;
		if (currentGrantTypes == null || currentGrantTypes.size() == 0) {
			this.client.setGrantTypes(null);
			return;
		}
		this.client.setGrantTypes(currentGrantTypes.toArray(new GrantType[currentGrantTypes.size()]));
	}

	private void updateCustomScripts() {
		List<CustomScript> currentCustomScripts = this.customScripts;
		if (currentCustomScripts == null || currentCustomScripts.size() == 0) {
			this.client.setDefaultAcrValues(null);
			return;
		}
		List<String> customScripts = new ArrayList<String>();
		for (CustomScript customScript : currentCustomScripts) {
			customScripts.add(customScript.getName());
		}
		this.client.setDefaultAcrValues(customScripts.toArray(new String[customScripts.size()]));
	}

	public void selectAddedClaims() {
		if (this.availableClaims == null) {
			return;
		}

		Set<String> addedClaimInums = new HashSet<String>();
		for (DisplayNameEntry claim : this.claims) {
			addedClaimInums.add(claim.getInum());
		}

		for (GluuAttribute aClaim : this.availableClaims) {
			aClaim.setSelected(addedClaimInums.contains(aClaim.getInum()));
		}
	}

	public void searchAvailableClaims() {
		if (Util.equals(this.oldSearchAvailableClaimPattern, this.searchAvailableClaimPattern)) {
			return;
		}

		try {
			this.availableClaims = attributeService.searchAttributes(this.searchAvailableClaimPattern,
					OxTrustConstants.searchClientsSizeLimit);
			this.oldSearchAvailableClaimPattern = this.searchAvailableClaimPattern;
			selectAddedClaims();
		} catch (Exception ex) {
			log.error("Failed to find attributes", ex);
		}
	}

	private List<DisplayNameEntry> getInitialClaimDisplayNameEntries() throws Exception {
		List<DisplayNameEntry> result = new ArrayList<DisplayNameEntry>();
		if ((client.getOxAuthClaims() == null) || (client.getOxAuthClaims().size() == 0)) {
			return result;
		}

		List<DisplayNameEntry> tmp = lookupService.getDisplayNameEntries(attributeService.getDnForAttribute(null),
				this.client.getOxAuthClaims());
		if (tmp != null) {
			result.addAll(tmp);
		}

		return result;
	}

	private List<ResponseType> getInitialResponseTypes() {
		List<ResponseType> result = new ArrayList<ResponseType>();

		ResponseType[] currentResponseTypes = this.client.getResponseTypes();
		if ((currentResponseTypes == null) || (currentResponseTypes.length == 0)) {
			return result;
		}

		result.addAll(Arrays.asList(currentResponseTypes));

		return result;
	}

	private List<GrantType> getInitialGrantTypes() {
		List<GrantType> result = new ArrayList<GrantType>();

		GrantType[] currentGrantTypes = this.client.getGrantTypes();
		if (currentGrantTypes == null || currentGrantTypes.length == 0) {
			return result;
		}

		result.addAll(Arrays.asList(currentGrantTypes));

		return result;
	}

	public void acceptSelectResponseTypes() {
		List<ResponseType> addedResponseTypes = getResponseTypes();

		for (SelectableEntity<ResponseType> availableResponseType : this.availableResponseTypes) {
			ResponseType responseType = availableResponseType.getEntity();
			if (availableResponseType.isSelected() && !addedResponseTypes.contains(responseType)) {
				addResponseType(responseType.getValue());
			}

			if (!availableResponseType.isSelected() && addedResponseTypes.contains(responseType)) {
				removeResponseType(responseType.getValue());
			}
		}
	}

	public void acceptSelectCustomScripts() {
		List<CustomScript> addedCustomScripts = getCustomScripts();
		for (SelectableEntity<CustomScript> availableCustomScript : this.availableCustomScripts) {
			CustomScript customScript = availableCustomScript.getEntity();
			if (availableCustomScript.isSelected() && !addedCustomScripts.contains(customScript)) {
				addCustomScript(customScript.getName());
			}
			if (!availableCustomScript.isSelected() && addedCustomScripts.contains(customScript)) {
				removeCustomScript(customScript.getName());
			}
		}
	}

	public void acceptSelectScopes() {
		List<OxAuthScope> addedScopes = getScopes();
		for (SelectableEntity<OxAuthScope> availableScope : this.availableScopes) {
			OxAuthScope scope = availableScope.getEntity();
			if (availableScope.isSelected() && !addedScopes.contains(scope)) {
				addScope(scope.getInum());
			}
			if (!availableScope.isSelected() && addedScopes.contains(scope)) {
				removeScope(scope.getInum());
			}
		}
	}

	private void addCustomScript(String name) {
		if (StringHelper.isEmpty(name)) {
			return;
		}
		CustomScript addCustomScript = new CustomScript();
		addCustomScript.setName(name);
		if (addCustomScript != null) {
			this.customScripts.add(addCustomScript);
		}
	}

	private void addScope(String inum) {
		if (StringHelper.isEmpty(inum)) {
			return;
		}
		OxAuthScope addScope = new OxAuthScope();
		try {
			addScope = scopeService.getScopeByInum(inum);
		} catch (Exception e) {
			e.printStackTrace();
		}
		addScope.setInum(inum);
		if (addScope != null) {
			this.scopes.add(addScope);
		}
	}

	public void removeCustomScript(String value) {
		if (StringHelper.isEmpty(value)) {
			return;
		}
		for (CustomScript customScript : customScripts) {
			if (customScript.getName().equalsIgnoreCase(value)) {
				this.customScripts.remove(customScript);
				break;
			}
		}
	}

	public void acceptSelectGrantTypes() {
		List<GrantType> addedGrantTypes = getGrantTypes();

		for (SelectableEntity<GrantType> availableGrantType : this.availableGrantTypes) {
			GrantType grantType = availableGrantType.getEntity();
			if (availableGrantType.isSelected() && !addedGrantTypes.contains(grantType)) {
				addGrantType(grantType.toString());
			}

			if (!availableGrantType.isSelected() && addedGrantTypes.contains(grantType)) {
				removeGrantType(grantType.toString());
			}
		}
	}

	public void cancelSelectCustomScripts() {
	}

	public void cancelSelectScopes() {
	}

	public void cancelSelectResponseTypes() {
	}

	public void cancelSelectGrantTypes() {
	}

	public void addResponseType(String value) {
		if (StringHelper.isEmpty(value)) {
			return;
		}

		ResponseType addResponseType = ResponseType.getByValue(value);
		if (addResponseType != null) {
			this.responseTypes.add(addResponseType);
		}
	}

	public void addGrantType(String value) {
		if (StringHelper.isEmpty(value)) {
			return;
		}

		GrantType addGrantType = GrantType.fromString(value);
		if (addGrantType != null) {
			this.grantTypes.add(addGrantType);
		}
	}

	public void removeResponseType(String value) {
		if (StringHelper.isEmpty(value)) {
			return;
		}

		ResponseType removeResponseType = ResponseType.getByValue(value);
		if (removeResponseType != null) {
			this.responseTypes.remove(removeResponseType);
		}
	}

	public void removeGrantType(String value) {
		if (StringHelper.isEmpty(value)) {
			return;
		}

		GrantType removeGrantType = GrantType.fromString(value);
		if (removeGrantType != null) {
			this.grantTypes.remove(removeGrantType);
		}
	}

	public void searchAvailableResponseTypes() {
		if (this.availableResponseTypes != null) {
			selectAddedResponseTypes();
			return;
		}

		List<SelectableEntity<ResponseType>> tmpAvailableResponseTypes = new ArrayList<SelectableEntity<ResponseType>>();

		for (ResponseType responseType : ResponseType.values()) {
			tmpAvailableResponseTypes.add(new SelectableEntity<ResponseType>(responseType));
		}

		this.availableResponseTypes = tmpAvailableResponseTypes;
		selectAddedResponseTypes();
	}

	public void searchAvailableCustomScripts() {
		if (this.availableCustomScripts != null) {
			selectAddedCustomScripts();
			return;
		}
		List<SelectableEntity<CustomScript>> tmpAvailableCustomScripts = new ArrayList<SelectableEntity<CustomScript>>();
		CustomScriptType[] allowedCustomScriptTypes = { CustomScriptType.PERSON_AUTHENTICATION };
		List<CustomScript> customScripts = customScriptService
				.findCustomScripts(Arrays.asList(allowedCustomScriptTypes));
		for (CustomScript customScript : customScripts) {
			tmpAvailableCustomScripts.add(new SelectableEntity<CustomScript>(customScript));
		}

		this.availableCustomScripts = tmpAvailableCustomScripts;
		selectAddedCustomScripts();
	}

	public void searchAvailableScopes() {
		if (this.availableScopes != null) {
			selectAddedScopes();
			return;
		}
		List<SelectableEntity<OxAuthScope>> tmpAvailableScopes = new ArrayList<SelectableEntity<OxAuthScope>>();
		List<OxAuthScope> scopes = new ArrayList<OxAuthScope>();
		try {
			scopes = scopeService.getAllScopesList();
		} catch (Exception e) {
			e.printStackTrace();
		}
		for (OxAuthScope scope : scopes) {
			tmpAvailableScopes.add(new SelectableEntity<OxAuthScope>(scope));
		}
		this.availableScopes = tmpAvailableScopes;
		selectAddedScopes();
	}

	public void searchAvailableGrantTypes() {
		if (this.availableGrantTypes != null) {
			selectAddedGrantTypes();
			return;
		}
		List<SelectableEntity<GrantType>> tmpAvailableGrantTypes = new ArrayList<SelectableEntity<GrantType>>();
		tmpAvailableGrantTypes.add(new SelectableEntity<GrantType>(GrantType.AUTHORIZATION_CODE));
		tmpAvailableGrantTypes.add(new SelectableEntity<GrantType>(GrantType.IMPLICIT));
		tmpAvailableGrantTypes.add(new SelectableEntity<GrantType>(GrantType.REFRESH_TOKEN));
		tmpAvailableGrantTypes.add(new SelectableEntity<GrantType>(GrantType.CLIENT_CREDENTIALS));
		tmpAvailableGrantTypes.add(new SelectableEntity<GrantType>(GrantType.RESOURCE_OWNER_PASSWORD_CREDENTIALS));
		tmpAvailableGrantTypes.add(new SelectableEntity<GrantType>(GrantType.OXAUTH_UMA_TICKET));
		this.availableGrantTypes = tmpAvailableGrantTypes;
		selectAddedGrantTypes();
	}

	private void selectAddedResponseTypes() {
		List<ResponseType> addedResponseTypes = getResponseTypes();
		for (SelectableEntity<ResponseType> availableResponseType : this.availableResponseTypes) {
			availableResponseType.setSelected(addedResponseTypes.contains(availableResponseType.getEntity()));
		}
	}

	public void selectAddedScopes() {
		List<OxAuthScope> addedScopes = getScopes();
		for (SelectableEntity<OxAuthScope> availableScope : this.availableScopes) {
			availableScope.setSelected(addedScopes.contains(availableScope.getEntity()));
		}
	}

	private void selectAddedCustomScripts() {
		List<CustomScript> addedCustomScripts = getCustomScripts();
		for (SelectableEntity<CustomScript> availableCustomScript : this.availableCustomScripts) {
			availableCustomScript.setSelected(addedCustomScripts.contains(availableCustomScript.getEntity()));
		}
	}

	private void selectAddedGrantTypes() {
		List<GrantType> addedGrantTypes = getGrantTypes();

		for (SelectableEntity<GrantType> availableGrantType : this.availableGrantTypes) {
			availableGrantType.setSelected(addedGrantTypes.contains(availableGrantType.getEntity()));
		}
	}

	public List<String> getClaimRedirectURIList() {
		return claimRedirectURIList;
	}

	public void setClaimRedirectURIList(List<String> claimRedirectURIList) {
		this.claimRedirectURIList = claimRedirectURIList;
	}

	public String getInum() {
		return inum;
	}

	public void setInum(String inum) {
		this.inum = inum;
	}

	public OxAuthClient getClient() {
		return client;
	}

	public boolean isUpdate() {
		return update;
	}

	public String getAvailableLoginUri() {
		return availableLoginUri;
	}

	public void setAvailableLoginUri(String availableLoginUri) {
		this.availableLoginUri = availableLoginUri;
	}

	public String getAvailableLogoutUri() {
		return availableLogoutUri;
	}

	public void setAvailableLogoutUri(String availableLogoutUri) {
		this.availableLogoutUri = availableLogoutUri;
	}

	public String getAvailableContact() {
		return availableContact;
	}

	public void setAvailableContact(String availableContact) {
		this.availableContact = availableContact;
	}

	public String getAvailableRequestUri() {
		return availableRequestUri;
	}

	public String availableAuthorizedOrigin() {
		return availableAuthorizedOrigin;
	}

	public void setAvailableRequestUri(String availableRequestUri) {
		this.availableRequestUri = availableRequestUri;
	}

	public List<SelectableEntity<OxAuthScope>> getAvailableScopes() {
		return this.availableScopes;
	}

	public List<GluuAttribute> getAvailableClaims() {
		return this.availableClaims;
	}

	public List<GluuGroup> getAvailableGroups() {
		return this.availableGroups;
	}

	public List<SelectableEntity<ResponseType>> getAvailableResponseTypes() {
		return this.availableResponseTypes;
	}

	public List<SelectableEntity<CustomScript>> getAvailableCustomScripts() {
		return this.availableCustomScripts;
	}

	public List<SelectableEntity<GrantType>> getAvailableGrantTypes() {
		return this.availableGrantTypes;
	}

	public List<String> getLoginUris() {
		return loginUris;
	}

	public List<String> getLogoutUris() {
		return logoutUris;
	}

	public List<OxAuthScope> getScopes() {
		return this.scopes;
	}

	public List<DisplayNameEntry> getClaims() {
		return this.claims;
	}

	public List<ResponseType> getResponseTypes() {
		return responseTypes;
	}

	public List<CustomScript> getCustomScripts() {
		return customScripts;
	}

	public List<GrantType> getGrantTypes() {
		return grantTypes;
	}

	public List<String> getContacts() {
		return contacts;
	}

	public List<String> getRequestUris() {
		return requestUris;
	}

	public List<String> getAuthorizedOrigins() {
		return authorizedOrigins;
	}

	public String getSearchAvailableClaimPattern() {
		return searchAvailableClaimPattern;
	}

	public void setSearchAvailableClaimPattern(String searchAvailableClaimPattern) {
		this.searchAvailableClaimPattern = searchAvailableClaimPattern;
	}

	/**
	 * @return the availableClientlogoutUri
	 */
	public String getAvailableClientlogoutUri() {
		return availableClientlogoutUri;
	}

	/**
	 * @param availableClientlogoutUri
	 *            the availableClientlogoutUri to set
	 */
	public void setAvailableClientlogoutUri(String availableClientlogoutUri) {
		this.availableClientlogoutUri = availableClientlogoutUri;
	}

	/**
	 * @return the clientlogoutUris
	 */
	public List<String> getClientlogoutUris() {
		return clientlogoutUris;
	}

	/**
	 * @param clientlogoutUris
	 *            the clientlogoutUris to set
	 */
	public void setClientlogoutUris(List<String> clientlogoutUris) {
		this.clientlogoutUris = clientlogoutUris;
	}

	/**
	 * All the Redirect Uris must match to return true.
	 */
	private boolean checkWhiteListRedirectUris(String redirectUri) {
		try {
			boolean valid = true;
			List<String> whiteList = appConfiguration.getClientWhiteList();
			URLPatternList urlPatternList = new URLPatternList(whiteList);

			// for (String redirectUri : redirectUris) {
			valid &= urlPatternList.isUrlListed(redirectUri);
			// }

			return valid;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * None of the Redirect Uris must match to return true.
	 */
	private boolean checkBlackListRedirectUris(String redirectUri) {
		try {
			boolean valid = true;
			List<String> blackList = appConfiguration.getClientBlackList();
			URLPatternList urlPatternList = new URLPatternList(blackList);

			// for (String redirectUri : redirectUris) {
			valid &= !urlPatternList.isUrlListed(redirectUri);
			// }

			return valid;
		} catch (Exception e) {
			return false;
		}

	}

	public boolean checkClientSecretRequired() {
		for (ResponseType responseType : this.responseTypes) {
			if (responseType.getValue().equalsIgnoreCase("token")
					|| responseType.getValue().equalsIgnoreCase("id_token")) {
				return false;
			}
		}

		for (GrantType grantType : this.grantTypes) {
			if (grantType.getValue().equalsIgnoreCase("implicit")) {
				return false;
			}

		}

		return true;
	}

	private void validateContacts() {
		String regex = "^[a-zA-Z0-9_!#$%&'*+/=?`{|}~^.-]+@[a-zA-Z0-9.-]+$";
		Pattern pattern = Pattern.compile(regex);
		List<String> tmpContactsList = new ArrayList<String>();
		boolean shouldShowWarning = false;
		for (String contact : contacts) {
			if (pattern.matcher(contact).matches()) {
				tmpContactsList.add(contact);
			} else {
				shouldShowWarning = true;
			}
		}
		contacts.clear();
		contacts.addAll(tmpContactsList);
		if (shouldShowWarning) {
			facesMessages.add(FacesMessage.SEVERITY_WARN, "Invalid contacts have been removed from contacts list");
		}
	}

	public void generatePassword() throws EncryptionException {
		String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
		String pwd = RandomStringUtils.random(24, characters);
		this.client.setOxAuthClientSecret(pwd);
		this.client.setEncodedClientSecret(encryptionService.encrypt(pwd));
	}

	public void setSecret(String pwd) throws EncryptionException {
		this.client.setOxAuthClientSecret(pwd);
		this.client.setEncodedClientSecret(encryptionService.encrypt(pwd));
	}

	public String getMarkDown() {
		StringBuilder sb = new StringBuilder();
		sb.append(new Heading("OPENID CONNECT CLIENTS DETAILS", 2)).append("\n");
		List<Object> items = new ArrayList<Object>();
		if (client.getDisplayName() != null && !client.getDisplayName().isEmpty()) {
			items.add("**Name:** " + client.getDisplayName());
		}
		if (client.getDescription() != null && !client.getDescription().isEmpty()) {
			items.add("**Description:** " + client.getDescription());
		}

		if (client.getInum() != null && !client.getInum().isEmpty()) {
			items.add("**Client ID:** " + client.getInum());
		}

		if (client.getSubjectType() != null && !client.getSubjectType().name().isEmpty()) {
			items.add("**Subject Type:** " + client.getSubjectType());
		}

		if (client.getClientSecretExpiresAt() != null && !client.getClientSecretExpiresAt().toString().isEmpty()) {
			items.add("**Expirattion date:** " + client.getClientSecretExpiresAt());
		}
		if (client.getOxAuthClientSecret() != null && !client.getOxAuthClientSecret().toString().isEmpty()) {
			items.add("**ClientSecret:** XXXXXXXXXXX");
		}

		if (client.getClientUri() != null && !client.getClientUri().toString().isEmpty()) {
			items.add("**Client Uri:** " + client.getClientUri());
		}
		if (client.getIdTokenTokenBindingCnf() != null && !client.getIdTokenTokenBindingCnf().toString().isEmpty()) {
			items.add("**TokenTokenBindingCnf:** " + client.getIdTokenTokenBindingCnf());
		}
		if (client.getOxAuthAppType().getValue() != null && !client.getOxAuthAppType().getValue().isEmpty()) {
			items.add("**Application Type:** " + client.getOxAuthAppType().getValue());
		}
		items.add("**Persist Client Authorizations:** " + client.getOxAuthPersistClientAuthorizations());
		items.add("**Pre-Authorization:** " + client.getOxAuthTrustedClient());
		items.add("**Authentication method for the Token Endpoint:** " + client.getTokenEndpointAuthMethod());
		items.add("**Logout Session Required:** " + client.getLogoutSessionRequired());
		items.add("**Include Claims In Id Token:** " + client.getOxIncludeClaimsInIdToken());
		items.add("**Disabled:** " + client.isDisabled());
		if (client.getLogoutUri() != null && !client.getLogoutUri().isEmpty()) {
			items.add("**Logout Uri:** " + client.getLogoutUri().toString());
		}
		if (client.getOxAuthPostLogoutRedirectURIs() != null && !client.getOxAuthPostLogoutRedirectURIs().isEmpty()) {
			items.add("**Logout Redirect URIs:** " + client.getOxAuthPostLogoutRedirectURIs().toString());
		}
		if (client.getOxAuthRedirectURIs() != null && !client.getOxAuthRedirectURIs().isEmpty()) {
			items.add("**Login Redirect URIs:** " + client.getOxAuthRedirectURIs().toString());
		}
		if (client.getOxAuthClaims() != null && !client.getOxAuthClaims().isEmpty()) {
			items.add("**Claims:** " + client.getOxAuthClaims().toString());
		}
		if (client.getAccessTokenSigningAlg() != null && !client.getAccessTokenSigningAlg().name().isEmpty()) {
			items.add("**AccessTokenSigningAlg:** " + client.getAccessTokenSigningAlg().name().toString());
		}
		if (client.getOxAuthScopes() != null && !client.getOxAuthScopes().isEmpty()) {
			List<String> scopes = new ArrayList<String>();
			for (OxAuthScope scope : this.scopes) {
				scopes.add(scope.getDisplayName());
			}
			items.add("**Scopes:** " + scopes.toString());
		}
		if (client.getGrantTypes() != null && client.getGrantTypes().length > 0) {
			items.add("**Grant types:** " + this.grantTypes.toString());
		}

		if (client.getResponseTypes() != null && client.getResponseTypes().length > 0) {
			items.add("**Response types:** " + this.responseTypes.toString());
		}
		if (client.getContacts() != null && !client.getContacts().toString().isEmpty()) {
			items.add("**Contacts:** " + this.contacts.toString());
		}
		if (client.getDefaultAcrValues() != null && client.getDefaultAcrValues().length > 0) {
			items.add("**DefaultAcrValues:** " + client.getDefaultAcrValues().toString());
		}
		sb.append(new UnorderedList<Object>(items)).append("\n");
		markDown = sb.toString();
		return markDown;
	}

	public void setMarkDown(String markDown) {
		this.markDown = markDown;
	}
}
