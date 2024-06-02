# cashu-mint (katika)

## Description
```Katika``` is a cashu mint implemented in java, based on the cashu-lib library. 

## Requirements
    $ java -version
```    
openjdk version "21.0.2" 2024-01-16
OpenJDK Runtime Environment (build 21.0.2+13-Ubuntu-123.10.1)
OpenJDK 64-Bit Server VM (build 21.0.2+13-Ubuntu-123.10.1, mixed mode, sharing)
```

    $ mvn -version
```
Apache Maven 3.8.7
Maven home: /usr/share/maven
Java version: 21.0.2, vendor: Private Build, runtime: /usr/lib/jvm/java-21-openjdk-amd64
Default locale: en_GB, platform encoding: UTF-8
OS name: "linux", version: "6.5.0-28-generic", arch: "amd64", family: "unix"
```

## Modules
- ```cashu-mint-admin```: admin interface
- ```cashu-mint-gateway```: gateway interface
- ```cashu-mint-gateway-mock```: mock gateway implementation
- ```cashu-mint-protocol```: protocol implementation
- ```cashu-mint-rest```: REST API with wallet endpoints
- ```cashu-mint-test```: unit test module (TODO)
- ```cashu-mint-vault```: vault implementation (currently under cashu-lib)

## Configuration


## Build and install cashu-mint
```
$ cd <your_git_home_dir>
$ git clone https://github.com/tcheeric/cashu-mint.git
$ cd cashu-mint
$ mvn clean install
$ cd cashu-mint-rest
$ mvn spring-boot:run
```

## Supported NUTs
- NUT-00: Notation, Utilization, and Terminology
- NUT-01: Mint public key exchange
- NUT-02: Keysets and keyset ID
- NUT-03: Swap tokens
- NUT-04: Mint tokens
- NUT-05: Melt tokens
- NUT-06: Mint information


## TODO
- Implement a native Lightning Network gateway
- Implement additional gateways (Mobile Money, Strike, Stripe)
- Migrate the vault implementation from ```cashu-lib``` to ```cashu-mint```
- Implement additional NUTs
- Implement a web admin interface
- Unit tests

## License
This project is licensed under the MIT License - see the [LICENSE.md](LICENSE.md) file for details.

## Disclaimer
This project is a work in progress and is not yet ready for production use. Use at your own risk.