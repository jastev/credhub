
# CredHub 

The CredHub server manages secrets like passwords, certificates, ssh keys, rsa keys, strings 
(arbitrary values), JSON blobs, and CAs. CredHub provides a REST API to get, set, generate, and securely store
such secrets.
 
* [CredHub Tracker](https://www.pivotaltracker.com/n/projects/1977341)
 
CredHub is intended to be deployed by [BOSH](bosh.io) using the [credhub-release](https://github.com/pivotal-cf/credhub-release) BOSH release. This repository is **not intended to be directly deployable** and is for development only.

Additional repos:

* [credhub-cli](https://github.com/cloudfoundry-incubator/credhub-cli): command line interface for credhub
* [credhub-release](https://github.com/pivotal-cf/credhub-release): BOSH release of CredHub server
* [credhub-acceptance-tests](https://github.com/cloudfoundry-incubator/credhub-acceptance-tests): integration tests written in Go.

## Development Notes

Launching in production directly using the `bootRun` target is **unsafe**, as you will launch with a `dev` profile, which has checked-in secret keys in `application-dev.yml`.

### Configuration

#### Generally

Configuration for the server is spread across the `application*.yml` files.

* Configuration shared by all environments (dev, test, or BOSH-deployed) is in `application.yml`. 
* Development-specific configuration is in `application-dev.yml`. This includes:
  * A UAA URL intended for development use only,
  * A JWT public verification key for use with that UAA, and
  * two `dev-key`s intended for development use only.
* Per-database configuration is placed in `application-dev-h2.yml`,`application-dev-mysql.yml`, and `application-dev-postgres.yml`. For convenience, these per-database profiles include the `dev` profile.

By default, CredHub launches with the `dev-h2` and `dev` profiles enabled.

#### Oracle JDK vs OpenJDK

CredHub relies on the JDK to have uncrippled cryptographic capability -- in the Oracle JDK, this requires the slightly deceptively named "Unlimited Strength Jurisdiction Policy".

By default, OpenJDK ships with "Unlimited Strength". Our credhub-release uses OpenJDK, and so inherits the full-strength capability.

But the Oracle JDK is often installed on workstations and does _not_ have the Unlimited Strength policy.

##### How can I tell?

If you see an error like `java.security.InvalidKeyException: Illegal key size`, you probably haven't installed the additional policy for the Oracle JDK. CredHub is trying to use 256-bit keys, but is being blocked by the default policy.

##### Resolving

Oracle makes the Unlimited Strength policy available for [separate download here](http://www.oracle.com/technetwork/java/javase/downloads/jce8-download-2133166.html).

Assuming you are on OS X, you can then run:

```
unzip ~/Downloads/jce_policy-8.zip -d /tmp
sudo cp /tmp/UnlimitedJCEPolicyJDK8/*.jar "$(/usr/libexec/java_home)/jre/lib/security/"
```

You will need to restart CredHub locally for changes to take effect.

#### UAA and the JWT public signing key

CredHub requires a [UAA server](https://github.com/cloudfoundry/uaa) to manage authentication.

In `application-dev.yml` there are two relevant settings:

1. `auth-server.url`. This needs to point to a running UAA server (remote or BOSH-lite, it's up to you).
2. `security.oauth2.resource.jwt.key-value`. This is the public verification key, corresponding to a private JWT signing key held by your UAA server.

For convenience, the CredHub team runs a public UAA whose IP is in the default `application-dev.yml` manifest. The login and password are `credhub`/`password`. This public UAA is for local development usage only! You will need to skip SSL validation in order to use it.

### Starting the server with different databases

#### H2 (the default)

H2 datasource configuration is in `application-dev-h2.yml`.

```sh
./start_server.sh
```

#### PostgreSQL

Postgres datasource configuration is in `application-dev-postgres.yml`.

Before development, you'll need to create the target database.

```sh
createdb credhub_dev
```

Then to run in development mode with Postgres

```sh
./start_server.sh -Dspring.profiles.active=dev-postgres
```

#### MariaDB

MariaDB datasource configuration is in `application-dev-mysql.yml`.

Log into your MariaDB server and create databases `credhub_dev` and `credhub_test` with privileges granted to `root`.

```shell
mysql -u root
create database credhub_test;
create database credhub_dev;
```

If you're on a Mac using Homebrew and you run into a problem where you install MariaDB and it isn't running (i.e., `mysql -u root` errors with a socket error), you may need to uninstall mysql, delete the `/usr/local/var/mysql` directory (*Warning: this will delete all local mysql & mariadb data!*), and then reinstall mariadb.

Then to run in development mode with MariaDB:

```sh
./start_server.sh -Dspring.profiles.active=dev-mysql
```

### Running tests with different databases

Testing with different databases requires you to set a system property with the profile corresponding to your desired database. For example, to test with H2, you'll need to run the tests with the `-Dspring.profiles.active=unit-test-h2` profile.

During development, it is helpful to set up different IntelliJ testing profiles that use the following VM Options:

- `-ea -Dspring.profiles.active=unit-test-h2` for testing with H2
- `-ea -Dspring.profiles.active=unit-test-mysql` for testing with MariaDB
- `-ea -Dspring.profiles.active=unit-test-postgres` for testing with Postgres

### Testing with the CLI and Acceptance Tests

#### Using the CLI locally

After having pulled the [credhub-cli](https://github.com/cloudfoundry-incubator/credhub-cli) repo, run `make`, and then run the following command to target your locally running CredHub instance:

```shell
build/credhub login -s https://localhost:9000 -u credhub -p password --skip-tls-validation
```

#### Running the Acceptance Tests

First, be sure to pull and compile the [credhub-cli](https://github.com/cloudfoundry-incubator/credhub-cli), as described above.

Make sure your development server is running. When it starts up for the first time, it will create a server CA and server certificate for SSL, as well as a trusted client CA for testing mutual TLS authentication. These will be located in `src/test/resources` relative to the `credhub` repository.

Pull [credhub-acceptance-tests](https://github.com/cloudfoundry-incubator/credhub-acceptance-tests) and run:

```shell
CREDENTIAL_ROOT=/path/to/credhub/repo/plus/src/test/resources ./run_tests.sh
```

Assuming it works, that will generate some test client certificates for testing mutual TLS (in `certs/` in the acceptance test directory) and run the acceptance test suite against your locally running credhub server.
