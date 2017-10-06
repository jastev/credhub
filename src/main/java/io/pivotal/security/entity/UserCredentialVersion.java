package io.pivotal.security.entity;

import io.pivotal.security.service.Encryption;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.DiscriminatorValue;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.OneToOne;
import javax.persistence.PrimaryKeyJoinColumn;
import javax.persistence.SecondaryTable;

@Entity
@DiscriminatorValue("user")
@SecondaryTable(
    name = UserCredentialVersion.TABLE_NAME,
    pkJoinColumns = {@PrimaryKeyJoinColumn(name = "uuid", referencedColumnName = "uuid")}
)
public class UserCredentialVersion extends CredentialVersion<UserCredentialVersion> {
  public static final String TABLE_NAME = "user_credential";
  public static final String CREDENTIAL_TYPE = "user";

  @Column(table = UserCredentialVersion.TABLE_NAME, length = 7000)
  private String username;

  @Column(table = UserCredentialVersion.TABLE_NAME, length = 20)
  private String salt;

  @OneToOne(cascade = CascadeType.ALL)
  @NotFound(action = NotFoundAction.IGNORE)
  @JoinColumn(table = UserCredentialVersion.TABLE_NAME, name = "password_parameters_uuid")
  private EncryptedValue encryptedGenerationParameters;

  public UserCredentialVersion() {
    this(null);
  }

  public UserCredentialVersion(String name) {
    super(name);
  }

  @Override
  public String getCredentialType() {
    return CREDENTIAL_TYPE;
  }

  public String getUsername() {
    return username;
  }

  public UserCredentialVersion setUsername(String username) {
    this.username = username;
    return this;
  }

  public UserCredentialVersion setSalt(String salt) {
    this.salt = salt;
    return this;
  }

  public String getSalt() {
    return salt;
  }

  public UserCredentialVersion setEncryptedGenerationParameters(
      Encryption encryptedGenerationParameters) {
    if (this.encryptedGenerationParameters == null){
      this.encryptedGenerationParameters = new EncryptedValue();
    }
    this.encryptedGenerationParameters.setEncryptedValue(encryptedGenerationParameters.encryptedValue);
    this.encryptedGenerationParameters.setEncryptionKeyUuid(encryptedGenerationParameters.canaryUuid);
    this.encryptedGenerationParameters.setNonce(encryptedGenerationParameters.nonce);
    return this;
  }

  public EncryptedValue getEncryptedGenerationParameters() {
    return encryptedGenerationParameters;
  }
}