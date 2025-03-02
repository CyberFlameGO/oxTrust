/*
 * oxTrust is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.gluu.oxtrust.ldap.cache.service;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.beanutils.BeanUtilsBean2;
import org.apache.commons.io.FilenameUtils;
import org.gluu.config.oxtrust.AppConfiguration;
import org.gluu.config.oxtrust.CacheRefreshAttributeMapping;
import org.gluu.config.oxtrust.CacheRefreshConfiguration;
import org.gluu.model.GluuStatus;
import org.gluu.model.SchemaEntry;
import org.gluu.model.custom.script.model.bind.BindCredentials;
import org.gluu.model.ldap.GluuLdapConfiguration;
import org.gluu.oxtrust.service.config.ConfigurationFactory;
import org.gluu.oxtrust.ldap.cache.model.CacheCompoundKey;
import org.gluu.oxtrust.ldap.cache.model.GluuInumMap;
import org.gluu.oxtrust.ldap.cache.model.GluuSimplePerson;
import org.gluu.oxtrust.model.GluuConfiguration;
import org.gluu.oxtrust.model.GluuCustomAttribute;
import org.gluu.oxtrust.model.GluuCustomPerson;
import org.gluu.oxtrust.service.ApplicationFactory;
import org.gluu.oxtrust.service.AttributeService;
import org.gluu.oxtrust.service.ConfigurationService;
import org.gluu.oxtrust.service.EncryptionService;
import org.gluu.oxtrust.service.InumService;
import org.gluu.oxtrust.service.PersonService;
import org.gluu.oxtrust.service.cdi.event.CacheRefreshEvent;
import org.gluu.oxtrust.service.external.ExternalCacheRefreshService;
import org.gluu.oxtrust.util.OxTrustConstants;
import org.gluu.oxtrust.util.PropertyUtil;
import org.gluu.persist.PersistenceEntryManager;
import org.gluu.persist.PersistenceEntryManagerFactory;
import org.gluu.persist.exception.BasePersistenceException;
import org.gluu.persist.exception.EntryPersistenceException;
import org.gluu.persist.exception.operation.SearchException;
import org.gluu.persist.ldap.impl.LdapEntryManager;
import org.gluu.persist.ldap.impl.LdapEntryManagerFactory;
import org.gluu.persist.ldap.operation.LdapOperationService;
import org.gluu.persist.model.SearchScope;
import org.gluu.persist.model.base.DummyEntry;
import org.gluu.persist.operation.PersistenceOperationService;
import org.gluu.search.filter.Filter;
import org.gluu.service.ObjectSerializationService;
import org.gluu.service.SchemaService;
import org.gluu.service.cdi.async.Asynchronous;
import org.gluu.service.cdi.event.Scheduled;
import org.gluu.service.timer.event.TimerEvent;
import org.gluu.service.timer.schedule.TimerSchedule;
import org.gluu.util.ArrayHelper;
import org.gluu.util.OxConstants;
import org.gluu.util.Pair;
import org.gluu.util.StringHelper;
import org.gluu.util.security.PropertiesDecrypter;
import org.slf4j.Logger;

/**
 * Check periodically if source servers contains updates and trigger target
 * server entry update if needed
 * 
 * @author Yuriy Movchan Date: 05.05.2011
 */
@ApplicationScoped
@Named
public class CacheRefreshTimer {

	private static final String LETTERS_FOR_SEARCH = "abcdefghijklmnopqrstuvwxyz1234567890.";
	private static final String[] TARGET_PERSON_RETURN_ATTRIBUTES = { OxTrustConstants.inum };

	private static final int DEFAULT_INTERVAL = 60;

	@Inject
	private Logger log;

	@Inject
	private Event<TimerEvent> timerEvent;

	@Inject
	protected ApplicationFactory applicationFactory;

	@Inject
	protected AttributeService attributeService;

	@Inject
	private ConfigurationFactory configurationFactory;

	@Inject
	private CacheRefreshService cacheRefreshService;

	@Inject
	private PersonService personService;

	@Inject
	private PersistenceEntryManager ldapEntryManager;

	@Inject
	private ConfigurationService configurationService;

	@Inject
	private CacheRefreshSnapshotFileService cacheRefreshSnapshotFileService;

	@Inject
	private ExternalCacheRefreshService externalCacheRefreshService;

	@Inject
	private SchemaService schemaService;

	@Inject
	private InumService inumService;

	@Inject
	private AppConfiguration appConfiguration;

	@Inject
	private EncryptionService encryptionService;

	@Inject
	private ObjectSerializationService objectSerializationService;

	private AtomicBoolean isActive;
	private long lastFinishedTime;

	public void initTimer() {
		log.info("Initializing Cache Refresh Timer");
		this.isActive = new AtomicBoolean(false);

		// Clean up previous Inum cache
		CacheRefreshConfiguration cacheRefreshConfiguration = configurationFactory.getCacheRefreshConfiguration();
		if (cacheRefreshConfiguration != null) {
			String snapshotFolder = cacheRefreshConfiguration.getSnapshotFolder();
			if (StringHelper.isNotEmpty(snapshotFolder)) {
				String inumCachePath = getInumCachePath(cacheRefreshConfiguration);
				objectSerializationService.cleanup(inumCachePath);
			}
		}

		// Schedule to start cache refresh every 1 minute
		timerEvent.fire(new TimerEvent(new TimerSchedule(DEFAULT_INTERVAL, DEFAULT_INTERVAL), new CacheRefreshEvent(),
				Scheduled.Literal.INSTANCE));

		this.lastFinishedTime = System.currentTimeMillis();
	}

	@Asynchronous
	public void process(@Observes @Scheduled CacheRefreshEvent cacheRefreshEvent) {
		if (this.isActive.get()) {
			log.debug("Another process is active");
			return;
		}

		if (!this.isActive.compareAndSet(false, true)) {
			log.debug("Failed to start process exclusively");
			return;
		}

		try {
			processInt();
		} finally {
			log.debug("Allowing to run new process exclusively");
			this.isActive.set(false);
		}
	}

	public void processInt() {
		CacheRefreshConfiguration cacheRefreshConfiguration = configurationFactory.getCacheRefreshConfiguration();
		try {
			GluuConfiguration currentConfiguration = configurationService.getConfiguration();
			if (!isStartCacheRefresh(cacheRefreshConfiguration, currentConfiguration)) {
				log.debug("Starting conditions aren't reached");
				return;
			}

			processImpl(cacheRefreshConfiguration, currentConfiguration);
			updateStatus(currentConfiguration, System.currentTimeMillis());

			this.lastFinishedTime = System.currentTimeMillis();
		} catch (Throwable ex) {
			log.error("Exception happened while executing cache refresh synchronization", ex);
		}
	}

	private boolean isStartCacheRefresh(CacheRefreshConfiguration cacheRefreshConfiguration,
			GluuConfiguration currentConfiguration) {
		if (!currentConfiguration.isVdsCacheRefreshEnabled()) {
			return false;
		}

		long poolingInterval = StringHelper.toInteger(currentConfiguration.getVdsCacheRefreshPollingInterval()) * 60 * 1000;
		if (poolingInterval < 0) {
			return false;
		}

		String cacheRefreshServerIpAddress = currentConfiguration.getCacheRefreshServerIpAddress();
		// if (StringHelper.isEmpty(cacheRefreshServerIpAddress)) {
		// log.debug("There is no master Cache Refresh server");
		// return false;
		// }

		// Compare server IP address with cacheRefreshServerIp
		boolean cacheRefreshServer = false;
		try {
			Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
			for (NetworkInterface networkInterface : Collections.list(nets)) {
				Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
				for (InetAddress inetAddress : Collections.list(inetAddresses)) {
					if (StringHelper.equals(cacheRefreshServerIpAddress, inetAddress.getHostAddress())) {
						cacheRefreshServer = true;
						break;
					}
				}

				if (cacheRefreshServer) {
					break;
				}
			}
		} catch (SocketException ex) {
			log.error("Failed to enumerate server IP addresses", ex);
		}

		if (!cacheRefreshServer) {
			cacheRefreshServer = externalCacheRefreshService.executeExternalIsStartProcessMethods();
		}

		if (!cacheRefreshServer) {
			log.debug("This server isn't master Cache Refresh server");
			return false;
		}

		// Check if cache refresh specific configuration was loaded
		if (cacheRefreshConfiguration == null) {
			log.info("Failed to start cache refresh. Can't loading configuration from oxTrustCacheRefresh.properties");
			return false;
		}

		long timeDiffrence = System.currentTimeMillis() - this.lastFinishedTime;

		return timeDiffrence >= poolingInterval;
	}

	private void processImpl(CacheRefreshConfiguration cacheRefreshConfiguration, GluuConfiguration currentConfiguration)
			throws SearchException {
		CacheRefreshUpdateMethod updateMethod = getUpdateMethod(cacheRefreshConfiguration);

		// Prepare and check connections to LDAP servers
		LdapServerConnection[] sourceServerConnections = prepareLdapServerConnections(cacheRefreshConfiguration,
				cacheRefreshConfiguration.getSourceConfigs());

		LdapServerConnection inumDbServerConnection;
		if (cacheRefreshConfiguration.isDefaultInumServer()) {
			GluuLdapConfiguration ldapInumConfiguration = new GluuLdapConfiguration();
			ldapInumConfiguration.setConfigId("local_inum");
			ldapInumConfiguration.setBaseDNsStringsList(
					Arrays.asList(new String[] { OxTrustConstants.CACHE_REFRESH_DEFAULT_BASE_DN }));

			inumDbServerConnection = prepareLdapServerConnection(cacheRefreshConfiguration, ldapInumConfiguration,
					true);
		} else {
			inumDbServerConnection = prepareLdapServerConnection(cacheRefreshConfiguration,
					cacheRefreshConfiguration.getInumConfig());
		}

		boolean isVdsUpdate = CacheRefreshUpdateMethod.VDS.equals(updateMethod);
		LdapServerConnection targetServerConnection = null;
		if (isVdsUpdate) {
			targetServerConnection = prepareLdapServerConnection(cacheRefreshConfiguration,
					cacheRefreshConfiguration.getTargetConfig());
		}

		try {
			if ((sourceServerConnections == null) || (inumDbServerConnection == null)
					|| (isVdsUpdate && (targetServerConnection == null))) {
				log.error("Skipping cache refresh due to invalid server configuration");
			} else {
				detectChangedEntries(cacheRefreshConfiguration, currentConfiguration, sourceServerConnections,
						inumDbServerConnection, targetServerConnection, updateMethod);
			}
		} finally {
			// Close connections to LDAP servers
			try {
				closeLdapServerConnection(sourceServerConnections);
			} catch (Exception e) {
				// Nothing can be done
			}

			if (!cacheRefreshConfiguration.isDefaultInumServer()) {
				try {
					closeLdapServerConnection(inumDbServerConnection);
				} catch (Exception e) {
					// Nothing can be done
				}
			}
			try {
				if (isVdsUpdate) {
					closeLdapServerConnection(targetServerConnection);
				}
			} catch (Exception e) {
				// Nothing can be done
			}
		}

		return;
	}

	@SuppressWarnings("unchecked")
	private boolean detectChangedEntries(CacheRefreshConfiguration cacheRefreshConfiguration,
			GluuConfiguration currentConfiguration, LdapServerConnection[] sourceServerConnections,
			LdapServerConnection inumDbServerConnection, LdapServerConnection targetServerConnection,
			CacheRefreshUpdateMethod updateMethod) throws SearchException {
		boolean isVDSMode = CacheRefreshUpdateMethod.VDS.equals(updateMethod);

		// Load all entries from Source servers
		log.info("Attempting to load entries from source server");
		List<GluuSimplePerson> sourcePersons;

		if (cacheRefreshConfiguration.isUseSearchLimit()) {
			sourcePersons = loadSourceServerEntries(cacheRefreshConfiguration, sourceServerConnections);
		} else {
			sourcePersons = loadSourceServerEntriesWithoutLimits(cacheRefreshConfiguration, sourceServerConnections);
		}

		log.info("Found '{}' entries in source server", sourcePersons.size());

		Map<CacheCompoundKey, GluuSimplePerson> sourcePersonCacheCompoundKeyMap = getSourcePersonCompoundKeyMap(
				cacheRefreshConfiguration, sourcePersons);
		log.info("Found '{}' unique entries in source server", sourcePersonCacheCompoundKeyMap.size());

		// Load all inum entries
		List<GluuInumMap> inumMaps = null;

		// Load all inum entries from local disk cache
		String inumCachePath = getInumCachePath(cacheRefreshConfiguration);
		Object loadedObject = objectSerializationService.loadObject(inumCachePath);
		if (loadedObject != null) {
			try {
				inumMaps = (List<GluuInumMap>) loadedObject;
				log.debug("Found '{}' entries in inum objects disk cache", inumMaps.size());
			} catch (Exception ex) {
				log.error("Failed to convert to GluuInumMap list", ex);
				objectSerializationService.cleanup(inumCachePath);
			}
		}

		if (inumMaps == null) {
			// Load all inum entries from LDAP
			inumMaps = loadInumServerEntries(cacheRefreshConfiguration, inumDbServerConnection);
			log.info("Found '{}' entries in inum server", inumMaps.size());
		}

		HashMap<CacheCompoundKey, GluuInumMap> primaryKeyAttrValueInumMap = getPrimaryKeyAttrValueInumMap(inumMaps);

		// Go through Source entries and create new InumMap entries if needed
		HashMap<CacheCompoundKey, GluuInumMap> addedPrimaryKeyAttrValueInumMap = addNewInumServerEntries(
				cacheRefreshConfiguration, inumDbServerConnection, sourcePersonCacheCompoundKeyMap,
				primaryKeyAttrValueInumMap);

		HashMap<CacheCompoundKey, GluuInumMap> allPrimaryKeyAttrValueInumMap = getAllInumServerEntries(
				primaryKeyAttrValueInumMap, addedPrimaryKeyAttrValueInumMap);
		log.debug("Count actual inum entries '{}' after updating inum server", allPrimaryKeyAttrValueInumMap.size());

		HashMap<String, Integer> currInumWithEntryHashCodeMap = getSourcePersonsHashCodesMap(inumDbServerConnection,
				sourcePersonCacheCompoundKeyMap, allPrimaryKeyAttrValueInumMap);
		log.debug("Count actual source entries '{}' after calculating hash code", currInumWithEntryHashCodeMap.size());

		// Create snapshots cache folder if needed
		boolean result = cacheRefreshSnapshotFileService.prepareSnapshotsFolder(cacheRefreshConfiguration);
		if (!result) {
			return false;
		}

		// Load last snapshot into memory
		Map<String, Integer> prevInumWithEntryHashCodeMap = cacheRefreshSnapshotFileService
				.readLastSnapshot(cacheRefreshConfiguration);

		// Compare 2 snapshot and invoke update if needed
		Set<String> changedInums = getChangedInums(currInumWithEntryHashCodeMap, prevInumWithEntryHashCodeMap,
				isVDSMode);
		log.info("Found '{}' changed entries", changedInums.size());

		// Load problem list from disk and add to changedInums
		List<String> problemInums = cacheRefreshSnapshotFileService.readProblemList(cacheRefreshConfiguration);
		if (problemInums != null) {
			log.info("Loaded '{}' problem entries from problem file", problemInums.size());
			// Process inums from problem list too
			changedInums.addAll(problemInums);
		}

		List<String> updatedInums = null;
		if (isVDSMode) {
			// Update request to VDS to update entries on target server
			updatedInums = updateTargetEntriesViaVDS(cacheRefreshConfiguration, targetServerConnection, changedInums);
		} else {
			updatedInums = updateTargetEntriesViaCopy(cacheRefreshConfiguration, sourcePersonCacheCompoundKeyMap,
					allPrimaryKeyAttrValueInumMap, changedInums);
		}

		log.info("Updated '{}' entries", updatedInums.size());
		changedInums.removeAll(updatedInums);
		log.info("Failed to update '{}' entries", changedInums.size());

		// Persist snapshot to cache folder
		result = cacheRefreshSnapshotFileService.createSnapshot(cacheRefreshConfiguration,
				currInumWithEntryHashCodeMap);
		if (!result) {
			return false;
		}

		// Retain only specified number of snapshots
		cacheRefreshSnapshotFileService.retainSnapshots(cacheRefreshConfiguration,
				cacheRefreshConfiguration.getSnapshotMaxCount());

		// Save changedInums as problem list to disk
		currentConfiguration.setVdsCacheRefreshProblemCount(String.valueOf(changedInums.size()));
		cacheRefreshSnapshotFileService.writeProblemList(cacheRefreshConfiguration, changedInums);

		// Prepare list of persons for removal
		List<GluuSimplePerson> personsForRemoval = null;

		boolean keepExternalPerson = cacheRefreshConfiguration.isKeepExternalPerson();
		log.debug("Keep external persons: '{}'", keepExternalPerson);
		if (keepExternalPerson) {
			// Determine entries which need to remove
			personsForRemoval = getRemovedPersons(currInumWithEntryHashCodeMap, prevInumWithEntryHashCodeMap);
		} else {
			// Process entries which don't exist in source server

			// Load all entries from Target server
			List<GluuSimplePerson> targetPersons = loadTargetServerEntries(cacheRefreshConfiguration, ldapEntryManager);
			log.info("Found '{}' entries in target server", targetPersons.size());

			// Detect entries which need to remove
			personsForRemoval = processTargetPersons(targetPersons, currInumWithEntryHashCodeMap);
		}
		log.debug("Count entries '{}' for removal from target server", personsForRemoval.size());

		// Remove entries from target server
		HashMap<String, GluuInumMap> inumInumMap = getInumInumMap(inumMaps);
		Pair<List<String>, List<String>> removeTargetEntriesResult = removeTargetEntries(inumDbServerConnection,
				ldapEntryManager, personsForRemoval, inumInumMap);
		List<String> removedPersonInums = removeTargetEntriesResult.getFirst();
		List<String> removedGluuInumMaps = removeTargetEntriesResult.getSecond();
		log.info("Removed '{}' persons from target server", removedPersonInums.size());

		// Prepare list of inum for serialization
		ArrayList<GluuInumMap> currentInumMaps = applyChangesToInumMap(inumInumMap, addedPrimaryKeyAttrValueInumMap,
				removedGluuInumMaps);

		// Strore all inum entries into local disk cache
		objectSerializationService.saveObject(inumCachePath, currentInumMaps);

		currentConfiguration
				.setVdsCacheRefreshLastUpdateCount(String.valueOf(updatedInums.size() + removedPersonInums.size()));

		return true;
	}

	private ArrayList<GluuInumMap> applyChangesToInumMap(HashMap<String, GluuInumMap> inumInumMap,
			HashMap<CacheCompoundKey, GluuInumMap> addedPrimaryKeyAttrValueInumMap, List<String> removedGluuInumMaps) {
		log.info("There are '{}' entries before updating inum list", inumInumMap.size());
		for (String removedGluuInumMap : removedGluuInumMaps) {
			inumInumMap.remove(removedGluuInumMap);
		}
		log.info("There are '{}' entries after removal '{}' entries", inumInumMap.size(), removedGluuInumMaps.size());

		ArrayList<GluuInumMap> currentInumMaps = new ArrayList<GluuInumMap>(inumInumMap.values());
		currentInumMaps.addAll(addedPrimaryKeyAttrValueInumMap.values());
		log.info("There are '{}' entries after adding '{}' entries", currentInumMaps.size(),
				addedPrimaryKeyAttrValueInumMap.size());

		return currentInumMaps;
	}

	private Set<String> getChangedInums(HashMap<String, Integer> currInumWithEntryHashCodeMap,
			Map<String, Integer> prevInumWithEntryHashCodeMap, boolean includeDeleted) {
		// Find chaged inums
		Set<String> changedInums = null;
		// First time run
		if (prevInumWithEntryHashCodeMap == null) {
			changedInums = new HashSet<String>(currInumWithEntryHashCodeMap.keySet());
		} else {
			changedInums = new HashSet<String>();

			// Add all inums which not exist in new snapshot
			if (includeDeleted) {
				for (String prevInumKey : prevInumWithEntryHashCodeMap.keySet()) {
					if (!currInumWithEntryHashCodeMap.containsKey(prevInumKey)) {
						changedInums.add(prevInumKey);
					}
				}
			}

			// Add all new inums and changed inums
			for (Entry<String, Integer> currEntry : currInumWithEntryHashCodeMap.entrySet()) {
				String currInumKey = currEntry.getKey();
				Integer prevHashCode = prevInumWithEntryHashCodeMap.get(currInumKey);
				if ((prevHashCode == null)
						|| ((prevHashCode != null) && !(prevHashCode.equals(currEntry.getValue())))) {
					changedInums.add(currInumKey);
				}
			}
		}
		return changedInums;
	}

	private List<GluuSimplePerson> getRemovedPersons(HashMap<String, Integer> currInumWithEntryHashCodeMap,
			Map<String, Integer> prevInumWithEntryHashCodeMap) {
		// First time run
		if (prevInumWithEntryHashCodeMap == null) {
			return new ArrayList<GluuSimplePerson>(0);
		}

		// Add all inums which not exist in new snapshot
		Set<String> deletedInums = new HashSet<String>();
		for (String prevInumKey : prevInumWithEntryHashCodeMap.keySet()) {
			if (!currInumWithEntryHashCodeMap.containsKey(prevInumKey)) {
				deletedInums.add(prevInumKey);
			}
		}

		List<GluuSimplePerson> deletedPersons = new ArrayList<GluuSimplePerson>(deletedInums.size());
		for (String deletedInum : deletedInums) {
			GluuSimplePerson person = new GluuSimplePerson();
			String personDn = personService.getDnForPerson(deletedInum);
			person.setDn(personDn);

			List<GluuCustomAttribute> customAttributes = new ArrayList<GluuCustomAttribute>();
			customAttributes.add(new GluuCustomAttribute(OxTrustConstants.inum, deletedInum));
			person.setCustomAttributes(customAttributes);

			deletedPersons.add(person);
		}

		return deletedPersons;
	}

	private List<String> updateTargetEntriesViaVDS(CacheRefreshConfiguration cacheRefreshConfiguration,
			LdapServerConnection targetServerConnection, Set<String> changedInums) {
		List<String> result = new ArrayList<String>();

		PersistenceEntryManager targetPersistenceEntryManager = targetServerConnection.getPersistenceEntryManager();
		Filter filter = cacheRefreshService.createObjectClassPresenceFilter();
		for (String changedInum : changedInums) {
			String baseDn = "action=synchronizecache," + personService.getDnForPerson(changedInum);
			try {
				targetPersistenceEntryManager.findEntries(baseDn, DummyEntry.class, filter, SearchScope.SUB, null,
						null, 0, 0, cacheRefreshConfiguration.getLdapSearchSizeLimit());
				result.add(changedInum);
				log.debug("Updated entry with inum {}", changedInum);
			} catch (BasePersistenceException ex) {
				log.error("Failed to update entry with inum '{}' using baseDN {}", changedInum, baseDn, ex);
			}
		}

		return result;
	}

	private List<String> updateTargetEntriesViaCopy(CacheRefreshConfiguration cacheRefreshConfiguration,
			Map<CacheCompoundKey, GluuSimplePerson> sourcePersonCacheCompoundKeyMap,
			HashMap<CacheCompoundKey, GluuInumMap> primaryKeyAttrValueInumMap, Set<String> changedInums) {
		HashMap<String, CacheCompoundKey> inumCacheCompoundKeyMap = getInumCacheCompoundKeyMap(
				primaryKeyAttrValueInumMap);
		Map<String, String> targetServerAttributesMapping = getTargetServerAttributesMapping(cacheRefreshConfiguration);
		String[] customObjectClasses = appConfiguration.getPersonObjectClassTypes();

		List<String> result = new ArrayList<String>();

		if (!validateTargetServerSchema(cacheRefreshConfiguration, targetServerAttributesMapping,
				customObjectClasses)) {
			return result;
		}

		for (String targetInum : changedInums) {
			CacheCompoundKey compoundKey = inumCacheCompoundKeyMap.get(targetInum);
			if (compoundKey == null) {
				continue;
			}

			GluuSimplePerson sourcePerson = sourcePersonCacheCompoundKeyMap.get(compoundKey);
			if (sourcePerson == null) {
				continue;
			}

			if (updateTargetEntryViaCopy(sourcePerson, targetInum, customObjectClasses,
					targetServerAttributesMapping)) {
				result.add(targetInum);
			}
		}

		return result;
	}

	private boolean validateTargetServerSchema(CacheRefreshConfiguration cacheRefreshConfiguration,
			Map<String, String> targetServerAttributesMapping, String[] customObjectClasses) {
		// Get list of return attributes
		String[] keyAttributesWithoutValues = getCompoundKeyAttributesWithoutValues(cacheRefreshConfiguration);
		String[] sourceAttributes = getSourceAttributes(cacheRefreshConfiguration);
		String[] returnAttributes = ArrayHelper.arrayMerge(keyAttributesWithoutValues, sourceAttributes);

		GluuSimplePerson sourcePerson = new GluuSimplePerson();
		for (String returnAttribute : returnAttributes) {
			sourcePerson.setAttribute(returnAttribute, "Test");
		}

		String targetInum = inumService.generateInums(OxTrustConstants.INUM_TYPE_PEOPLE_SLUG, false);
		String targetPersonDn = personService.getDnForPerson(targetInum);

		GluuCustomPerson targetPerson = new GluuCustomPerson();
		targetPerson.setDn(targetPersonDn);
		targetPerson.setInum(targetInum);
		targetPerson.setStatus(appConfiguration.getSupportedUserStatus().get(0));
		targetPerson.setCustomObjectClasses(customObjectClasses);

		// Update list of return attributes according mapping
		cacheRefreshService.setTargetEntryAttributes(sourcePerson, targetServerAttributesMapping, targetPerson);

		// Execute interceptor script
		externalCacheRefreshService.executeExternalUpdateUserMethods(targetPerson);
		boolean executionResult = externalCacheRefreshService.executeExternalUpdateUserMethods(targetPerson);
		if (!executionResult) {
			log.error("Failed to execute Cache Refresh scripts for person '{}'", targetInum);
			return false;
		}

		// Validate target server attributes
		List<GluuCustomAttribute> customAttributes = targetPerson.getCustomAttributes();

		List<String> targetAttributes = new ArrayList<String>(customAttributes.size());
		for (GluuCustomAttribute customAttribute : customAttributes) {
			targetAttributes.add(customAttribute.getName());
		}

		List<String> targetObjectClasses = Arrays
				.asList(ldapEntryManager.getObjectClasses(targetPerson, GluuCustomPerson.class));

		return validateTargetServerSchema(targetObjectClasses, targetAttributes);
	}

	private boolean validateTargetServerSchema(List<String> targetObjectClasses, List<String> targetAttributes) {
		SchemaEntry schemaEntry = schemaService.getSchema();
		if (schemaEntry == null) {
			// Destination server not requires schema validation
			return true;
		}

		Set<String> objectClassesAttributesSet = schemaService.getObjectClassesAttributes(schemaEntry,
				targetObjectClasses.toArray(new String[0]));

		Set<String> targetAttributesSet = new LinkedHashSet<String>();
		for (String attrbute : targetAttributes) {
			targetAttributesSet.add(StringHelper.toLowerCase(attrbute));
		}

		targetAttributesSet.removeAll(objectClassesAttributesSet);

		if (targetAttributesSet.size() == 0) {
			return true;
		}

		log.error("Skipping target entries update. Destination server schema doesn't has next attributes: '{}'",
				targetAttributesSet);

		return false;
	}

	private boolean updateTargetEntryViaCopy(GluuSimplePerson sourcePerson, String targetInum,
			String[] targetCustomObjectClasses, Map<String, String> targetServerAttributesMapping) {
		String targetPersonDn = personService.getDnForPerson(targetInum);
		GluuCustomPerson targetPerson = null;
		boolean updatePerson;
		if (personService.contains(targetPersonDn)) {
			try {
				targetPerson = personService.findPersonByDn(targetPersonDn);
				log.debug("Found person by inum '{}'", targetInum);
			} catch (EntryPersistenceException ex) {
				log.error("Failed to find person '{}'", targetInum, ex);
				return false;
			}
			updatePerson = true;
		} else {
			targetPerson = new GluuCustomPerson();
			targetPerson.setDn(targetPersonDn);
			targetPerson.setInum(targetInum);
			targetPerson.setStatus(appConfiguration.getSupportedUserStatus().get(0));
			updatePerson = false;
		}
		targetPerson.setCustomObjectClasses(targetCustomObjectClasses);

		targetPerson.setSourceServerName(sourcePerson.getSourceServerName());
		targetPerson.setSourceServerUserDn(sourcePerson.getDn());

		cacheRefreshService.setTargetEntryAttributes(sourcePerson, targetServerAttributesMapping, targetPerson);

		// Execute interceptor script
		boolean executionResult = externalCacheRefreshService.executeExternalUpdateUserMethods(targetPerson);
		if (!executionResult) {
			log.error("Failed to execute Cache Refresh scripts for person '{}'", targetInum);
			return false;
		}

		try {
			if (updatePerson) {
				personService.updatePerson(targetPerson);
				log.debug("Updated person '{}'", targetInum);
			} else {
				personService.addPerson(targetPerson);
				log.debug("Added new person '{}'", targetInum);
			}
		} catch (Exception ex) {
			log.error("Failed to '{}' person '{}'", updatePerson ? "update" : "add", targetInum, ex);
			return false;
		}

		return true;
	}

	private HashMap<String, CacheCompoundKey> getInumCacheCompoundKeyMap(
			HashMap<CacheCompoundKey, GluuInumMap> primaryKeyAttrValueInumMap) {
		HashMap<String, CacheCompoundKey> result = new HashMap<String, CacheCompoundKey>();

		for (Entry<CacheCompoundKey, GluuInumMap> primaryKeyAttrValueInumMapEntry : primaryKeyAttrValueInumMap
				.entrySet()) {
			result.put(primaryKeyAttrValueInumMapEntry.getValue().getInum(), primaryKeyAttrValueInumMapEntry.getKey());
		}

		return result;
	}

	private Pair<List<String>, List<String>> removeTargetEntries(LdapServerConnection inumDbServerConnection,
			PersistenceEntryManager targetPersistenceEntryManager, List<GluuSimplePerson> removedPersons,
			HashMap<String, GluuInumMap> inumInumMap) {

		Date runDate = new Date(this.lastFinishedTime);

		PersistenceEntryManager inumDbPersistenceEntryManager = inumDbServerConnection.getPersistenceEntryManager();
		List<String> result1 = new ArrayList<String>();
		List<String> result2 = new ArrayList<String>();

		for (GluuSimplePerson removedPerson : removedPersons) {
			String inum = removedPerson.getAttribute(OxTrustConstants.inum);

			// Update GluuInumMap if it exist
			GluuInumMap currentInumMap = inumInumMap.get(inum);
			if (currentInumMap == null) {
				log.warn("Can't find inum entry of person with DN: {}", removedPerson.getDn());
			} else {
				GluuInumMap removedInumMap = getMarkInumMapEntryAsRemoved(currentInumMap,
						ldapEntryManager.encodeTime(removedPerson.getDn(), runDate));
				try {
					inumDbPersistenceEntryManager.merge(removedInumMap);
					result2.add(removedInumMap.getInum());
				} catch (BasePersistenceException ex) {
					log.error("Failed to update entry with inum '{}' and DN: {}", currentInumMap.getInum(),
							currentInumMap.getDn(), ex);
					continue;
				}
			}

			// Remove person from target server
			try {
				targetPersistenceEntryManager.removeRecursively(removedPerson.getDn());
				result1.add(inum);
			} catch (BasePersistenceException ex) {
				log.error("Failed to remove person entry with inum '{}' and DN: {}", inum, removedPerson.getDn(), ex);
				continue;
			}

			log.debug("Person with DN: '{}' removed from target server", removedPerson.getDn());
		}

		return new Pair<List<String>, List<String>>(result1, result2);
	}

	private GluuInumMap getMarkInumMapEntryAsRemoved(GluuInumMap currentInumMap, String date) {
		GluuInumMap clonedInumMap;
		try {
			clonedInumMap = (GluuInumMap) BeanUtilsBean2.getInstance().cloneBean(currentInumMap);
		} catch (Exception ex) {
			log.error("Failed to prepare GluuInumMap for removal", ex);
			return null;
		}

		String suffix = "-" + date;

		String[] primaryKeyValues = ArrayHelper.arrayClone(clonedInumMap.getPrimaryKeyValues());
		String[] secondaryKeyValues = ArrayHelper.arrayClone(clonedInumMap.getSecondaryKeyValues());
		String[] tertiaryKeyValues = ArrayHelper.arrayClone(clonedInumMap.getTertiaryKeyValues());

		if (ArrayHelper.isNotEmpty(primaryKeyValues)) {
			markInumMapEntryKeyValuesAsRemoved(primaryKeyValues, suffix);
		}

		if (ArrayHelper.isNotEmpty(secondaryKeyValues)) {
			markInumMapEntryKeyValuesAsRemoved(secondaryKeyValues, suffix);
		}

		if (ArrayHelper.isNotEmpty(tertiaryKeyValues)) {
			markInumMapEntryKeyValuesAsRemoved(tertiaryKeyValues, suffix);
		}

		clonedInumMap.setPrimaryKeyValues(primaryKeyValues);
		clonedInumMap.setSecondaryKeyValues(secondaryKeyValues);
		clonedInumMap.setTertiaryKeyValues(tertiaryKeyValues);

		clonedInumMap.setStatus(GluuStatus.INACTIVE);

		return clonedInumMap;
	}

	private void markInumMapEntryKeyValuesAsRemoved(String[] keyValues, String suffix) {
		for (int i = 0; i < keyValues.length; i++) {
			keyValues[i] = keyValues[i] + suffix;
		}
	}

	private List<GluuInumMap> loadInumServerEntries(CacheRefreshConfiguration cacheRefreshConfiguration,
			LdapServerConnection inumDbServerConnection) {
		PersistenceEntryManager inumDbPersistenceEntryManager = inumDbServerConnection.getPersistenceEntryManager();
		String inumbaseDn = inumDbServerConnection.getBaseDns()[0];

		Filter filterObjectClass = Filter.createEqualityFilter(OxConstants.OBJECT_CLASS,
				OxTrustConstants.objectClassInumMap);
		Filter filterStatus = Filter.createNOTFilter(
				Filter.createEqualityFilter(OxTrustConstants.gluuStatus, GluuStatus.INACTIVE.getValue()));
		Filter filter = Filter.createANDFilter(filterObjectClass, filterStatus);

		return inumDbPersistenceEntryManager.findEntries(inumbaseDn, GluuInumMap.class, filter, SearchScope.SUB, null,
				null, 0, 0, cacheRefreshConfiguration.getLdapSearchSizeLimit());
	}

	private List<GluuSimplePerson> loadSourceServerEntriesWithoutLimits(
			CacheRefreshConfiguration cacheRefreshConfiguration, LdapServerConnection[] sourceServerConnections)
			throws SearchException {
		Filter customFilter = cacheRefreshService.createFilter(cacheRefreshConfiguration.getCustomLdapFilter());
		String[] keyAttributes = getCompoundKeyAttributes(cacheRefreshConfiguration);
		String[] keyAttributesWithoutValues = getCompoundKeyAttributesWithoutValues(cacheRefreshConfiguration);
		String[] keyObjectClasses = getCompoundKeyObjectClasses(cacheRefreshConfiguration);
		String[] sourceAttributes = getSourceAttributes(cacheRefreshConfiguration);

		String[] returnAttributes = ArrayHelper.arrayMerge(keyAttributesWithoutValues, sourceAttributes);

		Set<String> addedDns = new HashSet<String>();

		List<GluuSimplePerson> sourcePersons = new ArrayList<GluuSimplePerson>();
		for (LdapServerConnection sourceServerConnection : sourceServerConnections) {
			String sourceServerName = sourceServerConnection.getSourceServerName();

			PersistenceEntryManager sourcePersistenceEntryManager = sourceServerConnection.getPersistenceEntryManager();
			String[] baseDns = sourceServerConnection.getBaseDns();
			Filter filter = cacheRefreshService.createFilter(keyAttributes, keyObjectClasses, "", customFilter);
			if (log.isTraceEnabled()) {
				log.trace("Using next filter to load entris from source server: {}", filter);
			}

			for (String baseDn : baseDns) {
				List<GluuSimplePerson> currentSourcePersons = sourcePersistenceEntryManager.findEntries(baseDn,
						GluuSimplePerson.class, filter, SearchScope.SUB, returnAttributes, null, 0, 0,
						cacheRefreshConfiguration.getLdapSearchSizeLimit());

				// Add to result and ignore root entry if needed
				for (GluuSimplePerson currentSourcePerson : currentSourcePersons) {
					currentSourcePerson.setSourceServerName(sourceServerName);
					// if (!StringHelper.equalsIgnoreCase(baseDn,
					// currentSourcePerson.getDn())) {
					String currentSourcePersonDn = currentSourcePerson.getDn().toLowerCase();
					if (!addedDns.contains(currentSourcePersonDn)) {
						sourcePersons.add(currentSourcePerson);
						addedDns.add(currentSourcePersonDn);
					}
					// }
				}
			}
		}

		return sourcePersons;
	}

	private List<GluuSimplePerson> loadSourceServerEntries(CacheRefreshConfiguration cacheRefreshConfiguration,
			LdapServerConnection[] sourceServerConnections) throws SearchException {
		Filter customFilter = cacheRefreshService.createFilter(cacheRefreshConfiguration.getCustomLdapFilter());
		String[] keyAttributes = getCompoundKeyAttributes(cacheRefreshConfiguration);
		String[] keyAttributesWithoutValues = getCompoundKeyAttributesWithoutValues(cacheRefreshConfiguration);
		String[] keyObjectClasses = getCompoundKeyObjectClasses(cacheRefreshConfiguration);
		String[] sourceAttributes = getSourceAttributes(cacheRefreshConfiguration);

		String[] twoLettersArray = createTwoLettersArray();
		String[] returnAttributes = ArrayHelper.arrayMerge(keyAttributesWithoutValues, sourceAttributes);

		Set<String> addedDns = new HashSet<String>();

		List<GluuSimplePerson> sourcePersons = new ArrayList<GluuSimplePerson>();
		for (LdapServerConnection sourceServerConnection : sourceServerConnections) {
			String sourceServerName = sourceServerConnection.getSourceServerName();

			PersistenceEntryManager sourcePersistenceEntryManager = sourceServerConnection.getPersistenceEntryManager();
			String[] baseDns = sourceServerConnection.getBaseDns();
			for (String keyAttributeStart : twoLettersArray) {
				Filter filter = cacheRefreshService.createFilter(keyAttributes, keyObjectClasses, keyAttributeStart,
						customFilter);
				if (log.isDebugEnabled()) {
					log.trace("Using next filter to load entris from source server: {}", filter);
				}

				for (String baseDn : baseDns) {
					List<GluuSimplePerson> currentSourcePersons = sourcePersistenceEntryManager.findEntries(baseDn,
							GluuSimplePerson.class, filter, SearchScope.SUB, returnAttributes, null, 0, 0,
							cacheRefreshConfiguration.getLdapSearchSizeLimit());

					// Add to result and ignore root entry if needed
					for (GluuSimplePerson currentSourcePerson : currentSourcePersons) {
						currentSourcePerson.setSourceServerName(sourceServerName);
						// if (!StringHelper.equalsIgnoreCase(baseDn,
						// currentSourcePerson.getDn())) {
						String currentSourcePersonDn = currentSourcePerson.getDn().toLowerCase();
						if (!addedDns.contains(currentSourcePersonDn)) {
							sourcePersons.add(currentSourcePerson);
							addedDns.add(currentSourcePersonDn);
						}
						// }
					}
				}
			}
		}

		return sourcePersons;
	}

	private List<GluuSimplePerson> loadTargetServerEntries(CacheRefreshConfiguration cacheRefreshConfiguration,
			PersistenceEntryManager targetPersistenceEntryManager) {
		Filter filter = Filter.createEqualityFilter(OxConstants.OBJECT_CLASS, OxTrustConstants.objectClassPerson);

		return targetPersistenceEntryManager.findEntries(personService.getDnForPerson(null), GluuSimplePerson.class,
				filter, SearchScope.SUB, TARGET_PERSON_RETURN_ATTRIBUTES, null, 0, 0,
				cacheRefreshConfiguration.getLdapSearchSizeLimit());
	}

	private GluuInumMap addGluuInumMap(String inumbBaseDn, PersistenceEntryManager inumDbPersistenceEntryManager,
			String[] primaryKeyAttrName, String[][] primaryKeyValues) {
		String inum = cacheRefreshService.generateInumForNewInumMap(inumbBaseDn, inumDbPersistenceEntryManager);
		String inumDn = cacheRefreshService.getDnForInum(inumbBaseDn, inum);

		GluuInumMap inumMap = new GluuInumMap();
		inumMap.setDn(inumDn);
		inumMap.setInum(inum);
		inumMap.setPrimaryKeyAttrName(primaryKeyAttrName[0]);
		inumMap.setPrimaryKeyValues(primaryKeyValues[0]);
		if (primaryKeyAttrName.length > 1) {
			inumMap.setSecondaryKeyAttrName(primaryKeyAttrName[1]);
			inumMap.setSecondaryKeyValues(primaryKeyValues[1]);
		}
		if (primaryKeyAttrName.length > 2) {
			inumMap.setTertiaryKeyAttrName(primaryKeyAttrName[2]);
			inumMap.setTertiaryKeyValues(primaryKeyValues[2]);
		}
		inumMap.setStatus(GluuStatus.ACTIVE);
		cacheRefreshService.addInumMap(inumDbPersistenceEntryManager, inumMap);

		return inumMap;
	}

	private HashMap<CacheCompoundKey, GluuInumMap> addNewInumServerEntries(
			CacheRefreshConfiguration cacheRefreshConfiguration, LdapServerConnection inumDbServerConnection,
			Map<CacheCompoundKey, GluuSimplePerson> sourcePersonCacheCompoundKeyMap,
			HashMap<CacheCompoundKey, GluuInumMap> primaryKeyAttrValueInumMap) {
		PersistenceEntryManager inumDbPersistenceEntryManager = inumDbServerConnection.getPersistenceEntryManager();
		String inumbaseDn = inumDbServerConnection.getBaseDns()[0];

		HashMap<CacheCompoundKey, GluuInumMap> result = new HashMap<CacheCompoundKey, GluuInumMap>();

		String[] keyAttributesWithoutValues = getCompoundKeyAttributesWithoutValues(cacheRefreshConfiguration);
		for (Entry<CacheCompoundKey, GluuSimplePerson> sourcePersonCacheCompoundKeyEntry : sourcePersonCacheCompoundKeyMap
				.entrySet()) {
			CacheCompoundKey cacheCompoundKey = sourcePersonCacheCompoundKeyEntry.getKey();
			GluuSimplePerson sourcePerson = sourcePersonCacheCompoundKeyEntry.getValue();

			if (log.isTraceEnabled()) {
				log.trace("Checking source entry with key: '{}', and DN: {}", cacheCompoundKey, sourcePerson.getDn());
			}

			GluuInumMap currentInumMap = primaryKeyAttrValueInumMap.get(cacheCompoundKey);
			if (currentInumMap == null) {
				String[][] keyAttributesValues = getKeyAttributesValues(keyAttributesWithoutValues, sourcePerson);
				currentInumMap = addGluuInumMap(inumbaseDn, inumDbPersistenceEntryManager, keyAttributesWithoutValues,
						keyAttributesValues);
				result.put(cacheCompoundKey, currentInumMap);
				log.debug("Added new inum entry for DN: {}", sourcePerson.getDn());
			} else {
				log.trace("Inum entry for DN: '{}' exist", sourcePerson.getDn());
			}
		}

		return result;
	}

	private HashMap<CacheCompoundKey, GluuInumMap> getAllInumServerEntries(
			HashMap<CacheCompoundKey, GluuInumMap> primaryKeyAttrValueInumMap,
			HashMap<CacheCompoundKey, GluuInumMap> addedPrimaryKeyAttrValueInumMap) {
		HashMap<CacheCompoundKey, GluuInumMap> result = new HashMap<CacheCompoundKey, GluuInumMap>();

		result.putAll(primaryKeyAttrValueInumMap);
		result.putAll(addedPrimaryKeyAttrValueInumMap);

		return result;
	}

	private HashMap<String, Integer> getSourcePersonsHashCodesMap(LdapServerConnection inumDbServerConnection,
			Map<CacheCompoundKey, GluuSimplePerson> sourcePersonCacheCompoundKeyMap,
			HashMap<CacheCompoundKey, GluuInumMap> primaryKeyAttrValueInumMap) {
		PersistenceEntryManager inumDbPersistenceEntryManager = inumDbServerConnection.getPersistenceEntryManager();

		HashMap<String, Integer> result = new HashMap<String, Integer>();

		for (Entry<CacheCompoundKey, GluuSimplePerson> sourcePersonCacheCompoundKeyEntry : sourcePersonCacheCompoundKeyMap
				.entrySet()) {
			CacheCompoundKey cacheCompoundKey = sourcePersonCacheCompoundKeyEntry.getKey();
			GluuSimplePerson sourcePerson = sourcePersonCacheCompoundKeyEntry.getValue();

			GluuInumMap currentInumMap = primaryKeyAttrValueInumMap.get(cacheCompoundKey);

			result.put(currentInumMap.getInum(), inumDbPersistenceEntryManager.getHashCode(sourcePerson));
		}

		return result;
	}

	private List<GluuSimplePerson> processTargetPersons(List<GluuSimplePerson> targetPersons,
			HashMap<String, Integer> currInumWithEntryHashCodeMap) {
		List<GluuSimplePerson> result = new ArrayList<GluuSimplePerson>();

		for (GluuSimplePerson targetPerson : targetPersons) {
			String personInum = targetPerson.getAttribute(OxTrustConstants.inum);
			if (!currInumWithEntryHashCodeMap.containsKey(personInum)) {
				log.debug("Person with such DN: '{}' isn't present on source server", targetPerson.getDn());
				result.add(targetPerson);
			}
		}

		return result;
	}

	private HashMap<CacheCompoundKey, GluuInumMap> getPrimaryKeyAttrValueInumMap(List<GluuInumMap> inumMaps) {
		HashMap<CacheCompoundKey, GluuInumMap> result = new HashMap<CacheCompoundKey, GluuInumMap>();

		for (GluuInumMap inumMap : inumMaps) {
			result.put(new CacheCompoundKey(inumMap.getPrimaryKeyValues(), inumMap.getSecondaryKeyValues(),
					inumMap.getTertiaryKeyValues()), inumMap);
		}

		return result;
	}

	private HashMap<String, GluuInumMap> getInumInumMap(List<GluuInumMap> inumMaps) {
		HashMap<String, GluuInumMap> result = new HashMap<String, GluuInumMap>();

		for (GluuInumMap inumMap : inumMaps) {
			result.put(inumMap.getInum(), inumMap);
		}

		return result;
	}

	private Map<CacheCompoundKey, GluuSimplePerson> getSourcePersonCompoundKeyMap(
			CacheRefreshConfiguration cacheRefreshConfiguration, List<GluuSimplePerson> sourcePersons) {
		Map<CacheCompoundKey, GluuSimplePerson> result = new HashMap<CacheCompoundKey, GluuSimplePerson>();
		Set<CacheCompoundKey> duplicateKeys = new HashSet<CacheCompoundKey>();

		String[] keyAttributesWithoutValues = getCompoundKeyAttributesWithoutValues(cacheRefreshConfiguration);
		for (GluuSimplePerson sourcePerson : sourcePersons) {
			String[][] keyAttributesValues = getKeyAttributesValues(keyAttributesWithoutValues, sourcePerson);
			CacheCompoundKey cacheCompoundKey = new CacheCompoundKey(keyAttributesValues);

			if (result.containsKey(cacheCompoundKey)) {
				duplicateKeys.add(cacheCompoundKey);
			}

			result.put(cacheCompoundKey, sourcePerson);
		}

		for (CacheCompoundKey duplicateKey : duplicateKeys) {
			log.error("Non-deterministic primary key. Skipping user with key: {}", duplicateKey);
			result.remove(duplicateKey);
		}

		return result;
	}

	private LdapServerConnection[] prepareLdapServerConnections(CacheRefreshConfiguration cacheRefreshConfiguration,
			List<GluuLdapConfiguration> ldapConfigurations) {
		LdapServerConnection[] ldapServerConnections = new LdapServerConnection[ldapConfigurations.size()];
		for (int i = 0; i < ldapConfigurations.size(); i++) {
			ldapServerConnections[i] = prepareLdapServerConnection(cacheRefreshConfiguration,
					ldapConfigurations.get(i));
			if (ldapServerConnections[i] == null) {
				return null;
			}
		}

		return ldapServerConnections;
	}

	private LdapServerConnection prepareLdapServerConnection(CacheRefreshConfiguration cacheRefreshConfiguration,
			GluuLdapConfiguration ldapConfiguration) {
		return prepareLdapServerConnection(cacheRefreshConfiguration, ldapConfiguration, false);
	}

	private LdapServerConnection prepareLdapServerConnection(CacheRefreshConfiguration cacheRefreshConfiguration,
			GluuLdapConfiguration ldapConfiguration, boolean useLocalConnection) {
		String ldapConfig = ldapConfiguration.getConfigId();

		if (useLocalConnection) {
			return new LdapServerConnection(ldapConfig, ldapEntryManager, getBaseDNs(ldapConfiguration));
		}
		PersistenceEntryManagerFactory entryManagerFactory = applicationFactory
				.getPersistenceEntryManagerFactory(LdapEntryManagerFactory.class);
		String persistenceType = entryManagerFactory.getPersistenceType();

		Properties ldapProperties = toLdapProperties(entryManagerFactory, ldapConfiguration);
		Properties ldapDecryptedProperties = encryptionService.decryptAllProperties(ldapProperties);

		// Try to get updated password via script
		BindCredentials bindCredentials = externalCacheRefreshService
				.executeExternalGetBindCredentialsMethods(ldapConfig);
        String bindPasswordPropertyKey = persistenceType + "#" + PropertiesDecrypter.BIND_PASSWORD;
		if (bindCredentials != null) {
			log.error("Using updated password which got from getBindCredentials method");
			ldapDecryptedProperties.setProperty(persistenceType + ".bindDN", bindCredentials.getBindDn());
			ldapDecryptedProperties.setProperty(bindPasswordPropertyKey,
					bindCredentials.getBindPassword());
		}

		if (log.isTraceEnabled()) {
	        Properties clonedLdapDecryptedProperties = (Properties) ldapDecryptedProperties.clone();
	        if (clonedLdapDecryptedProperties.getProperty(bindPasswordPropertyKey) != null) {
	        	clonedLdapDecryptedProperties.setProperty(bindPasswordPropertyKey, "REDACTED");
	        }
			log.trace("Attempting to create PersistenceEntryManager with properties: {}", clonedLdapDecryptedProperties);
		}
		PersistenceEntryManager customPersistenceEntryManager = entryManagerFactory
				.createEntryManager(ldapDecryptedProperties);
		log.info("Created Cache Refresh PersistenceEntryManager: {}", customPersistenceEntryManager);

		if (!customPersistenceEntryManager.getOperationService().isConnected()) {
			log.error("Failed to connect to LDAP server using configuration {}", ldapConfig);
			return null;
		}

		return new LdapServerConnection(ldapConfig, customPersistenceEntryManager, getBaseDNs(ldapConfiguration));
	}

	private void closeLdapServerConnection(LdapServerConnection... ldapServerConnections) {
		for (LdapServerConnection ldapServerConnection : ldapServerConnections) {
			if ((ldapServerConnection != null) && (ldapServerConnection.getPersistenceEntryManager() != null)) {
				ldapServerConnection.getPersistenceEntryManager().destroy();
			}
		}
	}

	private String[] createTwoLettersArray() {
		char[] characters = LETTERS_FOR_SEARCH.toCharArray();
		int lettersCount = characters.length;

		String[] result = new String[lettersCount * lettersCount];
		for (int i = 0; i < lettersCount; i++) {
			for (int j = 0; j < lettersCount; j++) {
				result[i * lettersCount + j] = "" + characters[i] + characters[j];
			}
		}

		return result;
	}

	private String[][] getKeyAttributesValues(String[] attrs, GluuSimplePerson person) {
		String[][] result = new String[attrs.length][];
		for (int i = 0; i < attrs.length; i++) {
			result[i] = person.getAttributes(attrs[i]);
		}

		return result;
	}

	private void updateStatus(GluuConfiguration currentConfiguration, long lastRun) {
		GluuConfiguration configuration = configurationService.getConfiguration();
		Date currentDateTime = new Date();
		configuration.setVdsCacheRefreshLastUpdate(currentDateTime);
		configuration.setVdsCacheRefreshLastUpdateCount(currentConfiguration.getVdsCacheRefreshLastUpdateCount());
		configuration.setVdsCacheRefreshProblemCount(currentConfiguration.getVdsCacheRefreshProblemCount());
		configurationService.updateConfiguration(configuration);
	}

	private String getInumCachePath(CacheRefreshConfiguration cacheRefreshConfiguration) {
		return FilenameUtils.concat(cacheRefreshConfiguration.getSnapshotFolder(), "inum_cache.dat");
	}

	private class LdapServerConnection {
		private String sourceServerName;
		private PersistenceEntryManager ldapEntryManager;
		private String[] baseDns;

		protected LdapServerConnection(String sourceServerName, PersistenceEntryManager ldapEntryManager,
				String[] baseDns) {
			this.sourceServerName = sourceServerName;
			this.ldapEntryManager = ldapEntryManager;
			this.baseDns = baseDns;
		}

		public final String getSourceServerName() {
			return sourceServerName;
		}

		public final PersistenceEntryManager getPersistenceEntryManager() {
			return ldapEntryManager;
		}

		public final String[] getBaseDns() {
			return baseDns;
		}
	}

	private CacheRefreshUpdateMethod getUpdateMethod(CacheRefreshConfiguration cacheRefreshConfiguration) {
		String updateMethod = cacheRefreshConfiguration.getUpdateMethod();
		if (StringHelper.isEmpty(updateMethod)) {
			return CacheRefreshUpdateMethod.COPY;
		}

		return CacheRefreshUpdateMethod.getByValue(cacheRefreshConfiguration.getUpdateMethod());
	}

	private String[] getSourceAttributes(CacheRefreshConfiguration cacheRefreshConfiguration) {
		return cacheRefreshConfiguration.getSourceAttributes().toArray(new String[0]);
	}

	private String[] getCompoundKeyAttributes(CacheRefreshConfiguration cacheRefreshConfiguration) {
		return cacheRefreshConfiguration.getKeyAttributes().toArray(new String[0]);
	}

	private String[] getCompoundKeyObjectClasses(CacheRefreshConfiguration cacheRefreshConfiguration) {
		return cacheRefreshConfiguration.getKeyObjectClasses().toArray(new String[0]);
	}

	private String[] getCompoundKeyAttributesWithoutValues(CacheRefreshConfiguration cacheRefreshConfiguration) {
		String[] result = cacheRefreshConfiguration.getKeyAttributes().toArray(new String[0]);
		for (int i = 0; i < result.length; i++) {
			int index = result[i].indexOf('=');
			if (index != -1) {
				result[i] = result[i].substring(0, index);
			}
		}

		return result;
	}

	private Map<String, String> getTargetServerAttributesMapping(CacheRefreshConfiguration cacheRefreshConfiguration) {
		Map<String, String> result = new HashMap<String, String>();
		for (CacheRefreshAttributeMapping attributeMapping : cacheRefreshConfiguration.getAttributeMapping()) {
			result.put(attributeMapping.getDestination(), attributeMapping.getSource());
		}

		return result;
	}

	private Properties toLdapProperties(PersistenceEntryManagerFactory ldapEntryManagerFactory,
			GluuLdapConfiguration ldapConfiguration) {
		String persistenceType = ldapEntryManagerFactory.getPersistenceType();
		Properties ldapProperties = new Properties();
		ldapProperties.put(persistenceType + "#servers",
				PropertyUtil.simplePropertiesToCommaSeparatedList(ldapConfiguration.getServers()));
		ldapProperties.put(persistenceType + "#maxconnections",
				Integer.toString(ldapConfiguration.getMaxConnections()));
		ldapProperties.put(persistenceType + "#useSSL", Boolean.toString(ldapConfiguration.isUseSSL()));
		ldapProperties.put(persistenceType + "#bindDN", ldapConfiguration.getBindDN());
		ldapProperties.put(persistenceType + "#bindPassword", ldapConfiguration.getBindPassword());

		// Copy binary attributes list from main LDAP connection
		PersistenceOperationService persistenceOperationService = ldapEntryManager.getOperationService();
		if (persistenceOperationService instanceof LdapOperationService) {
			ldapProperties.put(persistenceType + "#binaryAttributes",
					PropertyUtil.stringsToCommaSeparatedList(((LdapOperationService) persistenceOperationService)
							.getConnectionProvider().getBinaryAttributes()));
		}

		return ldapProperties;
	}

	private String[] getBaseDNs(GluuLdapConfiguration ldapConfiguration) {
		return ldapConfiguration.getBaseDNsStringsList().toArray(new String[0]);
	}

}
