package org.cloudfoundry.credhub.data;

import org.apache.commons.lang3.StringUtils;
import org.cloudfoundry.credhub.audit.CEFAuditRecord;
import org.cloudfoundry.credhub.auth.UserContextHolder;
import org.cloudfoundry.credhub.domain.CredentialFactory;
import org.cloudfoundry.credhub.domain.CredentialVersion;
import org.cloudfoundry.credhub.entity.CertificateCredentialVersionData;
import org.cloudfoundry.credhub.entity.Credential;
import org.cloudfoundry.credhub.entity.CredentialVersionData;
import org.cloudfoundry.credhub.exceptions.ParameterizedValidationException;
import org.cloudfoundry.credhub.repository.CredentialVersionRepository;
import org.cloudfoundry.credhub.view.FindCertificateResult;
import org.cloudfoundry.credhub.view.FindCredentialResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.google.common.collect.Lists.newArrayList;

@Service
public class CredentialVersionDataService {

  private final CredentialVersionRepository credentialVersionRepository;
  private PermissionDataService permissionDataService;
  private final CredentialDataService credentialDataService;
  private final JdbcTemplate jdbcTemplate;
  private final CredentialFactory credentialFactory;
  private UserContextHolder userContextHolder;
  private CertificateVersionDataService certificateVersionDataService;
  private CEFAuditRecord auditRecord;

  @Value("${security.authorization.acls.enabled}")
  private boolean enforcePermissions;

  @Autowired
  protected CredentialVersionDataService(
      CredentialVersionRepository credentialVersionRepository,
      PermissionDataService permissionDataService,
      CredentialDataService credentialDataService,
      JdbcTemplate jdbcTemplate,
      CredentialFactory credentialFactory,
      UserContextHolder userContextHolder,
      CertificateVersionDataService certificateVersionDataService,
      CEFAuditRecord auditRecord) {
    this.credentialVersionRepository = credentialVersionRepository;
    this.permissionDataService = permissionDataService;
    this.credentialDataService = credentialDataService;
    this.jdbcTemplate = jdbcTemplate;
    this.credentialFactory = credentialFactory;
    this.userContextHolder = userContextHolder;
    this.certificateVersionDataService = certificateVersionDataService;
    this.auditRecord = auditRecord;
  }

  public <Z extends CredentialVersion> Z save(Z credentialVersion) {
    return (Z) credentialVersion.save(this);
  }

  public <Z extends CredentialVersion> Z save(CredentialVersionData credentialVersionData) {
    Credential credential = credentialVersionData.getCredential();

    if (credential.getUuid() == null) {
      credentialVersionData.setCredential(credentialDataService.save(credential));
    } else {
      CredentialVersion existingCredentialVersion = findMostRecent(credential.getName());
      if (existingCredentialVersion != null && !existingCredentialVersion.getCredentialType()
          .equals(credentialVersionData.getCredentialType())) {
        throw new ParameterizedValidationException("error.type_mismatch");
      }
    }

    return (Z) credentialFactory
        .makeCredentialFromEntity(credentialVersionRepository.saveAndFlush(credentialVersionData));
  }


  public CredentialVersion findMostRecent(String name) {
    Credential credential = credentialDataService.find(name);

    if (credential == null) {
      return null;
    } else {
      return credentialFactory.makeCredentialFromEntity(credentialVersionRepository
          .findFirstByCredentialUuidOrderByVersionCreatedAtDesc(credential.getUuid()));
    }
  }

  public CredentialVersion findByUuid(String uuid) {
    return credentialFactory
        .makeCredentialFromEntity(credentialVersionRepository.findOneByUuid(UUID.fromString(uuid)));
  }

  public List<String> findAllCertificateCredentialsByCaName(String caName) {
    return this.findCertificateNamesByCaName(caName);
  }

  public List<FindCredentialResult> findContainingName(String name) {
    return findMatchingName("%" + name + "%");
  }

  public List<FindCredentialResult> findStartingWithPath(String path) {
    return findStartingWithPath(path, "");
  }

  public List<FindCredentialResult> findStartingWithPath(String path, String expiresWithinDays) {
    path = StringUtils.prependIfMissing(path, "/");
    path = StringUtils.appendIfMissing(path, "/");

    if(!expiresWithinDays.equals("")) {
      return filterPermissions(filterCertificates(path, expiresWithinDays));
    }

    return filterPermissions(findMatchingName(path + "%"));
  }

  private List<FindCredentialResult> filterPermissions(List<FindCredentialResult> unfilteredResult) {
    if(!enforcePermissions){
      return unfilteredResult;
    }
    String actor = userContextHolder.getUserContext().getActor();
    return filterCredentials(unfilteredResult, permissionDataService.findAllPathsByActor(actor));
  }


  public boolean delete(String name) {
    return credentialDataService.delete(name);
  }

  public List<CredentialVersion> findAllByName(String name) {
    Credential credential = credentialDataService.find(name);

    return credential != null ? credentialFactory.makeCredentialsFromEntities(
        credentialVersionRepository.findAllByCredentialUuidOrderByVersionCreatedAtDesc(credential.getUuid()))
        : newArrayList();
  }

  public List<CredentialVersion> findNByName(String name, int numberOfVersions) {
    Credential credential = credentialDataService.find(name);

    if (credential != null) {
      List<CredentialVersionData> credentialVersionData = credentialVersionRepository
          .findAllByCredentialUuidOrderByVersionCreatedAtDesc(credential.getUuid())
          .stream()
          .limit(numberOfVersions)
          .collect(Collectors.toList());
      return credentialFactory.makeCredentialsFromEntities(credentialVersionData);
    } else {
      return newArrayList();
    }
  }

  public HashMap<UUID, Long> countByEncryptionKey() {
    HashMap<UUID, Long> map = new HashMap<>();
    jdbcTemplate.query(
        " SELECT count(*), encryption_key_uuid FROM credential_version " +
            "LEFT JOIN encrypted_value ON credential_version.encrypted_value_uuid = encrypted_value.uuid " +
            "GROUP BY encrypted_value.encryption_key_uuid",
        (rowSet, rowNum) -> map.put(UUID.fromString(rowSet.getString("encryption_key_uuid")), rowSet.getLong("count"))
    );
    return map;
  }

  public List<CredentialVersion> findActiveByName(String name) {
    Credential credential = credentialDataService.find(name);
    CredentialVersionData credentialVersionData;
    ArrayList<CredentialVersion> result = newArrayList();
    if (credential != null) {
      credentialVersionData = credentialVersionRepository
          .findFirstByCredentialUuidOrderByVersionCreatedAtDesc(credential.getUuid());

      if (credentialVersionData.getCredentialType().equals(CertificateCredentialVersionData.CREDENTIAL_TYPE)) {
        return certificateVersionDataService.findActiveWithTransitional(name);
      }
      result.add(credentialFactory.makeCredentialFromEntity(credentialVersionData));

      return result;
    } else {
      return newArrayList();
    }
  }

  public Long count() {
    return credentialVersionRepository.count();
  }

  public Long countEncryptedWithKeyUuidIn(Collection<UUID> uuids) {
    return credentialVersionRepository.countByEncryptedCredentialValueEncryptionKeyUuidIn(uuids);
  }

  //TODO: maybe refactor with next method
  //TODO: find where we need to pass the empty string when expiresWithinDays is not passed

  private List<FindCredentialResult> filterCredentials(List<FindCredentialResult> unfilteredResult, Set<String> permissions) {
    if(permissions.contains("/*")) {
      return unfilteredResult;
    }
    if(permissions.isEmpty()) {
      return new ArrayList<>();
    }

    List<FindCredentialResult> filteredResult = new ArrayList<>();

    for(FindCredentialResult credentialResult : unfilteredResult) {
      String credentialName = credentialResult.getName();
      if(permissions.contains(credentialName)){
        filteredResult.add(credentialResult);
      }

      for(String credentialPath : tokenizePath(credentialName)){
        if(permissions.contains(credentialPath)){
          filteredResult.add(credentialResult);
          break;
        }
      }
    }
    return filteredResult;
  }


  private List<FindCredentialResult> filterCertificates(String path, String expiresWithinDays) {
    Date currentDate = new Date();

    // convert date to calendar
    Calendar c = Calendar.getInstance();
    c.setTime(currentDate);

    c.add(Calendar.DATE, Integer.parseInt(expiresWithinDays));

    expiresWithinDays = c.getTime().toString();
//    System.out.println("*********************Expiry date: " + expiresWithinDays);
    expiresWithinDays = "2018-08-30";

        String query = "select name.name, credential_version.version_created_at, "
        + "certificate_credential.expiry_date from ("
        + "select max(version_created_at) as version_created_at,"
        + "credential_uuid, uuid"
        + "from credential_version group by credential_uuid, uuid"
        + ") as credential_version inner join ("
        + "select * from credential"
        + "where lower(name) like lower(?)) as name"
        + "on credential_version.credential_uuid = name.uuid"
        + "inner join ( select * from certificate_credential"
        + "where expiry_date <= ?"
	    + ") as certificate_credential on credential_version.uuid = certificate_credential.uuid"
        + "order by version_created_at desc";

    final List<FindCredentialResult> certificateResults = jdbcTemplate.query(query,
        new Object[]{path, expiresWithinDays},
        (rowSet, rowNum) -> {
          final Instant versionCreatedAt = Instant
              .ofEpochMilli(rowSet.getLong("version_created_at"));
          final String name = rowSet.getString("name");
          final Instant expiryDate = Instant
              .ofEpochMilli(rowSet.getLong("expiry_date"));
          return new FindCertificateResult(versionCreatedAt, name, expiryDate);
        }
    );

    return certificateResults;
  }


    private List<String> tokenizePath(String credentialName) {
    List<String> result = new ArrayList<>();
    String subPath;

    for(int i = 1; i < credentialName.length(); i++){
      if(credentialName.charAt(i) == '/'){
        subPath = credentialName.substring(0, i) + "/*";
        result.add(subPath);
      }
    }

    return result;
  }


  private List<FindCredentialResult> findMatchingName(String nameLike) {
    final List<FindCredentialResult> credentialResults = jdbcTemplate.query(
        " select name.name, credential_version.version_created_at from ("
            + "   select"
            + "     max(version_created_at) as version_created_at,"
            + "     credential_uuid"
            + "   from credential_version group by credential_uuid"
            + " ) as credential_version inner join ("
            + "   select * from credential"
            + "     where lower(name) like lower(?)"
            + " ) as name"
            + " on credential_version.credential_uuid = name.uuid"
            + " order by version_created_at desc",
        new Object[]{nameLike},
        (rowSet, rowNum) -> {
          final Instant versionCreatedAt = Instant
              .ofEpochMilli(rowSet.getLong("version_created_at"));
          final String name = rowSet.getString("name");
          return new FindCredentialResult(versionCreatedAt, name);
        }
    );
    return credentialResults;
  }

  private List<String> findCertificateNamesByCaName(String caName) {
    String query = "select distinct credential.name from "
        + "credential, credential_version, certificate_credential "
        + "where credential.uuid=credential_version.credential_uuid "
        + "and credential_version.uuid=certificate_credential.uuid "
        + "and lower(certificate_credential.ca_name) "
        + "like lower(?)";
    return jdbcTemplate.queryForList(query, String.class, caName);
  }
}
