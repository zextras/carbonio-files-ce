<!--
SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>

SPDX-License-Identifier: AGPL-3.0-only
-->

# Changelog

All notable changes to this project will be documented in this file. See [standard-version](https://github.com/conventional-changelog/standard-version) for commit guidelines.

### [0.8.2](https://github.com/Zextras/carbonio-files-ce/compare/v0.8.1...v0.8.2) (2023-04-27)


### Bug Fixes

* fix GetNode when a specific node version is given ([#58](https://github.com/Zextras/carbonio-files-ce/issues/58)) ([13cc3a0](https://github.com/Zextras/carbonio-files-ce/commit/13cc3a079643817b466d2f42a9561ddd65ba43cf))

### [0.8.1](https://github.com/Zextras/carbonio-files-ce/compare/v0.8.0...v0.8.1) (2023-03-31)


### Bug Fixes

* FILES-563 - Move jar in /usr/share/carbonio folder ([#51](https://github.com/Zextras/carbonio-files-ce/issues/51)) ([4fb1356](https://github.com/Zextras/carbonio-files-ce/commit/4fb13563189999dd1d4a20c628c36836ec562c70))
* FILES-618 add ExceptionHandler at the end of each endpoint pipeline ([#56](https://github.com/Zextras/carbonio-files-ce/issues/56)) ([c134a5e](https://github.com/Zextras/carbonio-files-ce/commit/c134a5ec6c43cc03f13e4ed1b0030d090dae2d04))

### [0.8.0](https://github.com/Zextras/carbonio-files-ce/compare/v0.7.0...v0.8.0) (2023-02-03)


### Features

* FILES-89 - Add owner_id and type parameters in findNodes ([#52](https://github.com/Zextras/carbonio-files-ce/issues/52)) ([75111c7](https://github.com/Zextras/carbonio-files-ce/commit/75111c7144fa6340160054e95bf55c949112c490))


### Bug Fixes

* FILES-572 - Fix tags definition in carbonio-files.hcl ([#53](https://github.com/Zextras/carbonio-files-ce/issues/53)) ([eea76b6](https://github.com/Zextras/carbonio-files-ce/commit/eea76b604052c2037949286b1e46f57759dd1f87))

### [0.7.0](https://github.com/Zextras/carbonio-files-ce/compare/v0.6.0...v0.7.0) (2022-11-24)


### Features

* FILES-462 - Setup project to run tests ([#45](https://github.com/Zextras/carbonio-files-ce/issues/45)) ([9de8a7d](https://github.com/Zextras/carbonio-files-ce/commit/9de8a7d26d65eff4e8f35251d2457a11687415df))
* FILES-484 - Add cache-control and etag headers in Preview responses ([#46](https://github.com/Zextras/carbonio-files-ce/issues/46)) ([577c5d4](https://github.com/Zextras/carbonio-files-ce/commit/577c5d4f8c6fe125225571cb257304fa48c6dbbb))

### [0.6.0](https://github.com/Zextras/carbonio-files-ce/compare/v0.5.0...v0.6.0) (2022-09-29)


### Features

* AR-62 - Add /metrics endpoint for monitoring with Prometheus ([641a139](https://github.com/Zextras/carbonio-files-ce/commit/641a1390efeebba66b2251f759aa419fc6d49353))


### Bug Fixes

* FILES-436 - Fix GetPath API to return nodes ordered correctly ([#41](https://github.com/Zextras/carbonio-files-ce/issues/41)) ([24283d5](https://github.com/Zextras/carbonio-files-ce/commit/24283d5d1ee2e804dd365c93d6e43ae8dfde11d0))
* FILES-437 - Fix timeout of copy API when file to copy is large ([#42](https://github.com/Zextras/carbonio-files-ce/issues/42)) ([b99eb8c](https://github.com/Zextras/carbonio-files-ce/commit/b99eb8cccc4ecc3bd2b903ec1c709a3a069bcbcd))

## [0.5.0](https://github.com/Zextras/carbonio-files-ce/compare/v0.4.1...v0.5.0) (2022-09-01)


### Features

* FILES-299 - Implement Collaboration link ([#39](https://github.com/Zextras/carbonio-files-ce/issues/39)) ([347a57d](https://github.com/Zextras/carbonio-files-ce/commit/347a57dd5860b02ea24dda266531fe1ff842ebbc)), closes [#35](https://github.com/Zextras/carbonio-files-ce/issues/35) [#37](https://github.com/Zextras/carbonio-files-ce/issues/37) [#38](https://github.com/Zextras/carbonio-files-ce/issues/38)

### [0.4.1](https://github.com/Zextras/carbonio-files-ce/compare/v0.4.0...v0.4.1) (2022-08-05)


### Bug Fixes

* FILES-354 - Fix RootId null when root is not LOCAL_ROOT ([#33](https://github.com/Zextras/carbonio-files-ce/issues/33)) ([86d851e](https://github.com/Zextras/carbonio-files-ce/commit/86d851e8a2f1593063c5e5d83dd222c9ac537e42))

## [0.4.0](https://github.com/Zextras/carbonio-files-ce/compare/v0.3.7...v0.4.0) (2022-07-22)


### Features

* FILES-194 - Reduce response latencies of GraphQL APIs ([#30](https://github.com/Zextras/carbonio-files-ce/issues/30)) ([f15a2c7](https://github.com/Zextras/carbonio-files-ce/commit/f15a2c7bee9407333bdcd81cec11f8c3e3d18502))
* FILES-290 - Implement getAccountsByEmail API ([#25](https://github.com/Zextras/carbonio-files-ce/issues/25)) ([aea3f0a](https://github.com/Zextras/carbonio-files-ce/commit/aea3f0a9b36fd469896d036b8ec627c97e5b6ea3))
* FILES-320 - Align pom version to package version ([#26](https://github.com/Zextras/carbonio-files-ce/issues/26)) ([cdc591f](https://github.com/Zextras/carbonio-files-ce/commit/cdc591fc43c992533b68697e08c569fc31e21a2d))
* FILES-325 - Improve how the database schema is initialized ([#29](https://github.com/Zextras/carbonio-files-ce/issues/29)) ([254347a](https://github.com/Zextras/carbonio-files-ce/commit/254347a79495e5ea476fbe80db6a78f1486c4d24))


### Bug Fixes

* FILES-311 - Fix download timeout on large files ([#31](https://github.com/Zextras/carbonio-files-ce/issues/31)) ([0e9f5c3](https://github.com/Zextras/carbonio-files-ce/commit/0e9f5c33347d70444b9eb244923c9cf0062b482e))
* FILES-354 - Fix GetChildren API called on LOCAL_ROOT folder ([#32](https://github.com/Zextras/carbonio-files-ce/issues/32)) ([a4f4fc4](https://github.com/Zextras/carbonio-files-ce/commit/a4f4fc450e822b81fe58de584eaceb0f23b19dda))

### [0.3.7](https://github.com/Zextras/carbonio-files-ce/compare/v0.3.6...v0.3.7) (2022-06-09)


### Features

* FILES-283 - Propagate boot log configuration ([#21](https://github.com/Zextras/carbonio-files-ce/issues/21)) ([fe1ac2e](https://github.com/Zextras/carbonio-files-ce/commit/fe1ac2e6e47d0d5c3e131daee3e24e8553471936))
* FILES-33 - Implement max version logic ([#11](https://github.com/Zextras/carbonio-files-ce/issues/11)) ([60f0465](https://github.com/Zextras/carbonio-files-ce/commit/60f04650b39f27df228f027eb6da996f2645a28f))


### Bug Fixes

* CopyNodes does not maintain the name when there are conflicts ([#23](https://github.com/Zextras/carbonio-files-ce/issues/23)) ([8a50cff](https://github.com/Zextras/carbonio-files-ce/commit/8a50cff75b7b11d2cbeaacf82e1e24a2b693d508))
* FILES-114 - Delete shared nodes ([#22](https://github.com/Zextras/carbonio-files-ce/issues/22)) ([8c4a552](https://github.com/Zextras/carbonio-files-ce/commit/8c4a5529349c74d90c2530a17b15b7406fbba6ce))
* Fix Jenkinsfile in order to upload the rpm correctly ([#24](https://github.com/Zextras/carbonio-files-ce/issues/24)) ([814c90f](https://github.com/Zextras/carbonio-files-ce/commit/814c90f63d581b04dda69131ab8e3148ccb8ed3d))

### [0.3.6](https://github.com/Zextras/carbonio-files-ce/compare/v0.3.5...v0.3.6) (2022-05-24)


### Bug Fixes

* Return 404 when the preview is not available ([#20](https://github.com/Zextras/carbonio-files-ce/issues/20)) ([4a07f43](https://github.com/Zextras/carbonio-files-ce/commit/4a07f43502f77c6a4b76f5a2c2a162343750a367))

### [0.3.5](https://github.com/Zextras/carbonio-files-ce/compare/v0.3.4...v0.3.5) (2022-05-24)


### Bug Fixes

* FILES-27 FILES-71 FILES-115 - Fix findNodes and sorting ([#17](https://github.com/Zextras/carbonio-files-ce/issues/17)) ([b28eb45](https://github.com/Zextras/carbonio-files-ce/commit/b28eb452de303a2eb3490a39682a1f97705b0ac8))
* Fix the creation of ancestor_ids during the copy operation ([#18](https://github.com/Zextras/carbonio-files-ce/issues/18)) ([5dad817](https://github.com/Zextras/carbonio-files-ce/commit/5dad817965aed1725130a9b845e12751c97abe6e))

### [0.3.4](https://github.com/Zextras/carbonio-files-ce/compare/v0.3.3...v0.3.4) (2022-05-16)


### Features

* FILES-198 - Implement health, health/ready, health/live endpoints ([#14](https://github.com/Zextras/carbonio-files-ce/issues/14)) ([1e9e7a6](https://github.com/Zextras/carbonio-files-ce/commit/1e9e7a679a50cda8f5374469c7ccbf40f702417d))


### Bug Fixes

* FILES-112 - Avoid creating folder with the same name ([#13](https://github.com/Zextras/carbonio-files-ce/issues/13)) ([a71ae9b](https://github.com/Zextras/carbonio-files-ce/commit/a71ae9bb7e13780a5d65b1e564106e498b2df99d))
* FILES-229 - Fix NullPointer when a search is paginated ([#15](https://github.com/Zextras/carbonio-files-ce/issues/15)) ([d459338](https://github.com/Zextras/carbonio-files-ce/commit/d459338d7e6fa779a07c94b6e593f7bea6dae088))
* FILES-263 - Fix preview/thumbnail requests in bulk ([#12](https://github.com/Zextras/carbonio-files-ce/issues/12)) ([92ab8c3](https://github.com/Zextras/carbonio-files-ce/commit/92ab8c3d30b4a431329a1eb6674b4f1825330768))
* In health/ready/ API, replace log level from debug to info ([#16](https://github.com/Zextras/carbonio-files-ce/issues/16)) ([bd21c99](https://github.com/Zextras/carbonio-files-ce/commit/bd21c994f64bf60620a99051fbf5bcbf8b4e9435))

### [0.3.3](https://github.com/Zextras/carbonio-files-ce/compare/v0.3.2...v0.3.3) (2022-05-10)


### Features

* Add intentions for mailbox and videoserver-recorder ([#10](https://github.com/Zextras/carbonio-files-ce/issues/10)) ([076ff51](https://github.com/Zextras/carbonio-files-ce/commit/076ff516684683e37f82a3ce01b7aaa2aad9f01d))
* FILES-247 - Expose service discover configs with GraphQL ([#9](https://github.com/Zextras/carbonio-files-ce/issues/9)) ([55a94b5](https://github.com/Zextras/carbonio-files-ce/commit/55a94b5723f30736414ffb9a901a02800c1aa390))

### [0.3.2](https://github.com/Zextras/carbonio-files-ce/compare/v0.3.1...v0.3.2) (2022-05-09)


### Features

* FILES-220 - Implement document preview api ([#7](https://github.com/Zextras/carbonio-files-ce/issues/7)) ([86791d5](https://github.com/Zextras/carbonio-files-ce/commit/86791d519fc91b8b1ca9a11d0069834db3db6916))


### Bug Fixes

* Pass the right owner id to the preview SKD ([#8](https://github.com/Zextras/carbonio-files-ce/issues/8)) ([996a3dd](https://github.com/Zextras/carbonio-files-ce/commit/996a3dd3804051a84a93b4e095fc9184c423917d))

### [0.3.1](https://github.com/Zextras/carbonio-files-ce/compare/v0.3.0...v0.3.1) (2022-04-13)


### Features

* FILES-196 - Implement upload-to API to push files in mailbox store ([#4](https://github.com/Zextras/carbonio-files-ce/issues/4)) ([b8cd9f4](https://github.com/Zextras/carbonio-files-ce/commit/b8cd9f487714dc1855ca85d91b0b70aee0ee8d92))


### Bug Fixes

* FILES-218 - setup log configuration ([#5](https://github.com/Zextras/carbonio-files-ce/issues/5)) ([0f44f50](https://github.com/Zextras/carbonio-files-ce/commit/0f44f50049aae986a022fb4b1215ab3c1da5acb5))
* FILES-221 - Fix download and public link permissions ([#6](https://github.com/Zextras/carbonio-files-ce/issues/6)) ([6bb62fc](https://github.com/Zextras/carbonio-files-ce/commit/6bb62fc1347abdc3a36ada7f679259d85b964e5d))

## [0.3.0](https://github.com/Zextras/carbonio-files-ce/compare/v0.2.0...v0.3.0) (2022-04-05)


### Features

* FILES-187 - Update carbonio-preview-sdk ([ae39750](https://github.com/Zextras/carbonio-files-ce/commit/ae397501bb8f08c76a6dca0b02e12ef897b09c93))
* FILES-187 - Update carbonio-preview-sdk ([7455ac2](https://github.com/Zextras/carbonio-files-ce/commit/7455ac25538763559d22cf9c43773134a60b8808))
* FILES-214 - Update carbonio-storages-sdk ([f0c3368](https://github.com/Zextras/carbonio-files-ce/commit/f0c3368ea00baa2fc14a49792564d1dea8edbffb))
* FILES-214 - Update carbonio-storages-sdk ([6e8d1be](https://github.com/Zextras/carbonio-files-ce/commit/6e8d1bea82ed54b95b18bc642c404bdddd3c1040))


### Bug Fixes

* FILES-155 - Update config and fix user-management-sdk version ([8ca4beb](https://github.com/Zextras/carbonio-files-ce/commit/8ca4bebef4136e28007fcd766ef44b8af1b472ad))
* FILES-155 - Update config, fix user-management-sdk version ([22a4b4b](https://github.com/Zextras/carbonio-files-ce/commit/22a4b4b7789e24229a32a33c1ded3fe842a92f48))
* Refactor how the config is loaded ([f4287ae](https://github.com/Zextras/carbonio-files-ce/commit/f4287ae911e21bae8014756ac18b4632ab77c7ef))
