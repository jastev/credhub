package org.cloudfoundry.credhub.service;

import org.cloudfoundry.credhub.audit.CEFAuditRecord;
import org.cloudfoundry.credhub.audit.entity.GetCredentialById;
import org.cloudfoundry.credhub.auth.UserContextHolder;
import org.cloudfoundry.credhub.constants.CredentialType;
import org.cloudfoundry.credhub.constants.CredentialWriteMode;
import org.cloudfoundry.credhub.credential.CredentialValue;
import org.cloudfoundry.credhub.data.CertificateAuthorityService;
import org.cloudfoundry.credhub.data.CredentialDataService;
import org.cloudfoundry.credhub.data.CredentialVersionDataService;
import org.cloudfoundry.credhub.domain.CertificateCredentialVersion;
import org.cloudfoundry.credhub.domain.CredentialFactory;
import org.cloudfoundry.credhub.domain.CredentialVersion;
import org.cloudfoundry.credhub.entity.Credential;
import org.cloudfoundry.credhub.exceptions.EntryNotFoundException;
import org.cloudfoundry.credhub.exceptions.InvalidQueryParameterException;
import org.cloudfoundry.credhub.exceptions.ParameterizedValidationException;
import org.cloudfoundry.credhub.exceptions.PermissionException;
import org.cloudfoundry.credhub.request.*;
import org.cloudfoundry.credhub.view.FindCredentialResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

import static org.cloudfoundry.credhub.request.PermissionOperation.DELETE;
import static org.cloudfoundry.credhub.request.PermissionOperation.READ;
import static org.cloudfoundry.credhub.request.PermissionOperation.WRITE;
import static org.cloudfoundry.credhub.request.PermissionOperation.WRITE_ACL;

@Service
public class PermissionedCredentialService {

  private final CredentialVersionDataService credentialVersionDataService;

  private final CredentialFactory credentialFactory;
  private final CertificateAuthorityService certificateAuthorityService;
  private PermissionCheckingService permissionCheckingService;
  private final UserContextHolder userContextHolder;
  private final CredentialDataService credentialDataService;
  private final CEFAuditRecord auditRecord;

  @Autowired
  public PermissionedCredentialService(
      CredentialVersionDataService credentialVersionDataService,
      CredentialFactory credentialFactory,
      PermissionCheckingService permissionCheckingService,
      CertificateAuthorityService certificateAuthorityService,
      UserContextHolder userContextHolder,
      CredentialDataService credentialDataService,
      CEFAuditRecord auditRecord) {
    this.credentialVersionDataService = credentialVersionDataService;
    this.credentialFactory = credentialFactory;
    this.permissionCheckingService = permissionCheckingService;
    this.certificateAuthorityService = certificateAuthorityService;
    this.userContextHolder = userContextHolder;
    this.credentialDataService = credentialDataService;
    this.auditRecord = auditRecord;
  }

  public CredentialVersion save(
      CredentialVersion existingCredentialVersion,
      CredentialValue credentialValue,
      BaseCredentialRequest generateRequest) {
    boolean shouldWriteNewCredential = shouldWriteNewCredential(existingCredentialVersion, generateRequest);

    validateCredentialSave(generateRequest.getName(), generateRequest.getType(), existingCredentialVersion);

    if (!shouldWriteNewCredential) {
      return existingCredentialVersion;
    }

    return makeAndSaveNewCredential(existingCredentialVersion, credentialValue, generateRequest);
  }

  public boolean delete(String credentialName) {
    if (!permissionCheckingService.hasPermission(userContextHolder.getUserContext().getActor(), credentialName, DELETE)) {
      throw new EntryNotFoundException("error.credential.invalid_access");
    }
    return credentialVersionDataService.delete(credentialName);
  }

  public List<CredentialVersion> findAllByName(String credentialName) {
    if (!permissionCheckingService.hasPermission(userContextHolder.getUserContext().getActor(), credentialName, READ)) {
      throw new EntryNotFoundException("error.credential.invalid_access");
    }

    List<CredentialVersion> credentialList = credentialVersionDataService.findAllByName(credentialName);

    for (CredentialVersion credentialVersion : credentialList) {
      auditRecord.addVersion(credentialVersion);
      auditRecord.addResource(credentialVersion.getCredential());
    }

    return credentialList;
  }

  public List<CredentialVersion> findNByName(String credentialName, Integer numberOfVersions) {
    if (numberOfVersions < 0) {
      throw new InvalidQueryParameterException("error.invalid_query_parameter", "versions");
    }

    if (!permissionCheckingService.hasPermission(userContextHolder.getUserContext().getActor(), credentialName, READ)) {
      throw new EntryNotFoundException("error.credential.invalid_access");
    }

    return credentialVersionDataService.findNByName(credentialName, numberOfVersions);
  }

  public List<CredentialVersion> findActiveByName(String credentialName) {
    if (!permissionCheckingService.hasPermission(userContextHolder.getUserContext().getActor(), credentialName, READ)) {
      throw new EntryNotFoundException("error.credential.invalid_access");
    }
    List<CredentialVersion> credentialList = credentialVersionDataService.findActiveByName(credentialName);

    for (CredentialVersion credentialVersion : credentialList) {
      auditRecord.addVersion(credentialVersion);
      auditRecord.addResource(credentialVersion.getCredential());
    }

    return credentialList;
  }

  public Credential findByUuid(UUID credentialUUID) {
    Credential credential = credentialDataService.findByUUID(credentialUUID);
    if (credential == null) {
      throw new EntryNotFoundException("error.credential.invalid_access");
    }

    if (!permissionCheckingService.hasPermission(userContextHolder.getUserContext().getActor(), credential.getName(), READ)) {
      throw new EntryNotFoundException("error.credential.invalid_access");
    }
    return credential;
  }

  public CredentialVersion findVersionByUuid(String credentialUUID) {
    CredentialVersion credentialVersion = credentialVersionDataService.findByUuid(credentialUUID);

    auditRecord.setRequestDetails(new GetCredentialById(credentialUUID));

    if (credentialVersion != null) {
      auditRecord.setVersion(credentialVersion);
      auditRecord.setResource(credentialVersion.getCredential());
    } else {
      throw new EntryNotFoundException("error.credential.invalid_access");
    }

    String credentialName = credentialVersion.getName();

    if (!permissionCheckingService.hasPermission(userContextHolder.getUserContext().getActor(), credentialName, READ)) {
      throw new EntryNotFoundException("error.credential.invalid_access");
    }
    return credentialVersionDataService.findByUuid(credentialUUID);
  }

  public List<String> findAllCertificateCredentialsByCaName(String caName) {
    if (!permissionCheckingService.hasPermission(userContextHolder.getUserContext().getActor(), caName, PermissionOperation.READ)) {
      throw new EntryNotFoundException("error.credential.invalid_access");
    }

    return credentialVersionDataService.findAllCertificateCredentialsByCaName(caName);
  }

  public List<FindCredentialResult> findStartingWithPath(String path) {
    return findStartingWithPath(path, "");
  }

  public List<FindCredentialResult> findStartingWithPath(String path, String expiresWithinDays) {
    return credentialVersionDataService.findStartingWithPath(path, expiresWithinDays);
  }


  public List<FindCredentialResult> findContainingName(String name) {
    return credentialVersionDataService.findContainingName(name);
  }

  public CredentialVersion findMostRecent(String credentialName) {
    return credentialVersionDataService.findMostRecent(credentialName);
  }

  private CredentialVersion makeAndSaveNewCredential(CredentialVersion existingCredentialVersion,
      CredentialValue credentialValue, BaseCredentialRequest request) {
    CredentialVersion newVersion = credentialFactory.makeNewCredentialVersion(
        CredentialType.valueOf(request.getType()),
        request.getName(),
        credentialValue,
        existingCredentialVersion,
        request.getGenerationParameters());
    return credentialVersionDataService.save(newVersion);
  }

  private boolean shouldWriteNewCredential(CredentialVersion existingCredentialVersion, BaseCredentialRequest request) {
    if(request instanceof BaseCredentialSetRequest) {
      return true;
    }

    if (existingCredentialVersion == null) {
      return true;
    }

    if(request instanceof BaseCredentialGenerateRequest) {
      BaseCredentialGenerateRequest generateRequest = (BaseCredentialGenerateRequest) request;

      if(generateRequest.getMode() != null && generateRequest.getMode().equals(CredentialWriteMode.NO_OVERWRITE)) {
        return false;
      }

      if(generateRequest.getMode() != null && generateRequest.getMode().equals(CredentialWriteMode.OVERWRITE)) {
        return true;
      }
    }

    if (existingCredentialVersion instanceof CertificateCredentialVersion) {
      final CertificateCredentialVersion certificateCredentialVersion = (CertificateCredentialVersion) existingCredentialVersion;
      if (certificateCredentialVersion.getCaName() != null) {
        boolean updatedCA = !certificateCredentialVersion.getCa().equals(
            certificateAuthorityService.findActiveVersion(certificateCredentialVersion.getCaName()).getCertificate());
        if (updatedCA) {
          return true;
        }
      }
    }

    if (!existingCredentialVersion.matchesGenerationParameters(request.getGenerationParameters())) {
      return true;
    }


      BaseCredentialGenerateRequest generateRequest = (BaseCredentialGenerateRequest) request;
      return generateRequest.isOverwrite();

  }

  private void validateCredentialSave(String credentialName, String type, CredentialVersion existingCredentialVersion) {
    verifyWritePermission(credentialName);

    if (existingCredentialVersion != null && !existingCredentialVersion.getCredentialType().equals(type)) {
      throw new ParameterizedValidationException("error.type_mismatch");
    }
  }

  private void verifyWritePermission(String credentialName) {
    if(userContextHolder.getUserContext() == null){
      return;
    }

    if (!permissionCheckingService.hasPermission(userContextHolder.getUserContext().getActor(), credentialName, WRITE)) {
      throw new PermissionException("error.credential.invalid_access");
    }
  }

  private void verifyWriteAclPermission(String credentialName) {
    if (!permissionCheckingService.hasPermission(userContextHolder.getUserContext().getActor(), credentialName, WRITE_ACL)) {
      throw new PermissionException("error.credential.invalid_access");
    }
  }
}
