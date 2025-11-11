## [0.17.0](https://github.com/zextras/carbonio-files-ce/compare/v0.16.0...v0.17.0) (2025-08-21)

### Features

* build packages via docker ([#180](https://github.com/zextras/carbonio-files-ce/issues/180)) ([b8fae4f](https://github.com/zextras/carbonio-files-ce/commit/b8fae4f2f94cf971331a156edb6c75669a0a4415))
* implement multiple nodes download ([#181](https://github.com/zextras/carbonio-files-ce/issues/181)) ([87efe6c](https://github.com/zextras/carbonio-files-ce/commit/87efe6c512f20b04c50c13a33987a60de478ee2c))
* working minimal ([#173](https://github.com/zextras/carbonio-files-ce/issues/173)) ([9d7b78f](https://github.com/zextras/carbonio-files-ce/commit/9d7b78f8375f8c8fa27eaa2f8e065755a210989f))

### Bug Fixes

* rename docker tag release jenkinsfile ([#175](https://github.com/zextras/carbonio-files-ce/issues/175)) ([7c33c75](https://github.com/zextras/carbonio-files-ce/commit/7c33c75498f2ea7b66a9b7de2e99bf5e72eaf9f0))
* revert WantedBy for compatibility with older systems ([#182](https://github.com/zextras/carbonio-files-ce/issues/182)) ([6536893](https://github.com/zextras/carbonio-files-ce/commit/65368937e7f56e30a73dfcfecccfb59cc68858e4))
## [0.16.0](https://github.com/zextras/carbonio-files-ce/compare/v0.15.1...v0.16.0) (2025-05-19)

### Features

* add redirect on public link download if link has access code ([#157](https://github.com/zextras/carbonio-files-ce/issues/157)) ([afa833a](https://github.com/zextras/carbonio-files-ce/commit/afa833a6f4b37bb7af8ff629b61974ff7a34f98b))
* allow domain admin to define the maximum size of uploadable items in files ([#165](https://github.com/zextras/carbonio-files-ce/issues/165)) ([3f6630d](https://github.com/zextras/carbonio-files-ce/commit/3f6630d23207528c4fa258e438f97761c0843c92))
* create internal upload file endpoint ([#168](https://github.com/zextras/carbonio-files-ce/issues/168)) ([35c6660](https://github.com/zextras/carbonio-files-ce/commit/35c66608a13cf8bc48bb4a14145d3a9b212f9efc))
* implement notifications for Added, Removed and Shared nodes ([#167](https://github.com/zextras/carbonio-files-ce/issues/167)) ([a10d0bd](https://github.com/zextras/carbonio-files-ce/commit/a10d0bdd56f706ca4560bd02b67275a5ae83b4fa))
* manage different account status over files ([#164](https://github.com/zextras/carbonio-files-ce/issues/164)) ([3700428](https://github.com/zextras/carbonio-files-ce/commit/370042846dd7e0888e11b08268c5eb41eda6dd09))

### Bug Fixes

* add lang tag to preview requests to display correct dates ([#163](https://github.com/zextras/carbonio-files-ce/issues/163)) ([15c8a17](https://github.com/zextras/carbonio-files-ce/commit/15c8a17537728e17a984c588eec97cd0a8671a86))
* disable introspection on graphql queries ([#159](https://github.com/zextras/carbonio-files-ce/issues/159)) ([cfeba77](https://github.com/zextras/carbonio-files-ce/commit/cfeba7784e28098e7a2720ac21c5b064048747ba))
* implemented signed page token ([#162](https://github.com/zextras/carbonio-files-ce/issues/162)) ([b90bb4b](https://github.com/zextras/carbonio-files-ce/commit/b90bb4b848e6acc897d5025a7d9dd659d0fac953))
* public links now blocked if resource is trashed ([#161](https://github.com/zextras/carbonio-files-ce/issues/161)) ([f87e4f1](https://github.com/zextras/carbonio-files-ce/commit/f87e4f129be358526c2038ba9a3313a5b219edf7))
## [0.15.1](https://github.com/zextras/carbonio-files-ce/compare/v0.15.0...v0.15.1) (2025-03-06)

### Bug Fixes

* removed duplication of nodes from public dir on infras with C type collate ([#156](https://github.com/zextras/carbonio-files-ce/issues/156)) ([c735a50](https://github.com/zextras/carbonio-files-ce/commit/c735a5050215bb2d3cd185feddb78648ec5ce49e))
## [0.15.0](https://github.com/zextras/carbonio-files-ce/compare/v0.14.4...v0.15.0) (2025-02-06)

### Features

* add new message mimetypes on nodetype enum ([#150](https://github.com/zextras/carbonio-files-ce/issues/150)) ([02eeac2](https://github.com/zextras/carbonio-files-ce/commit/02eeac23bb75275d3c60bf4cc3efdb5e15e69538)), closes [NodeType#MESSAGE](https://github.com/zextras/NodeType/issues/MESSAGE)
* implement delete all api for account deletion ([#154](https://github.com/zextras/carbonio-files-ce/issues/154)) ([8dd1a71](https://github.com/zextras/carbonio-files-ce/commit/8dd1a71ff745e19c6352cd736ceb622c564eb323))
* links public id from 32 to 50 characters ([#139](https://github.com/zextras/carbonio-files-ce/issues/139)) ([0646d26](https://github.com/zextras/carbonio-files-ce/commit/0646d26af1e87c6964c57ce2a9243dd4ee621b2e))
* make node version as a query param in Preview APIs ([#151](https://github.com/zextras/carbonio-files-ce/issues/151)) ([51bce0b](https://github.com/zextras/carbonio-files-ce/commit/51bce0bd9978e772f4059df08e7763a6159230c3))

### Bug Fixes

* **ci:** restore distinct packages for distinct distros. ([48d211c](https://github.com/zextras/carbonio-files-ce/commit/48d211c5041972bbab92959ba01cc8a6dd6770d5))
* **ci:** separate ubuntu builds for each flavour ([#152](https://github.com/zextras/carbonio-files-ce/issues/152)) ([5ad71be](https://github.com/zextras/carbonio-files-ce/commit/5ad71be80edb51ab16caeb591d1f52db46f8ac6e))
* create link api does now checks the maximum number of links that can be created ([#148](https://github.com/zextras/carbonio-files-ce/issues/148)) ([54ca9bf](https://github.com/zextras/carbonio-files-ce/commit/54ca9bf5f9fab700083e8075f495f28be025366c))
* handle errors on flagnodes ([#149](https://github.com/zextras/carbonio-files-ce/issues/149)) ([02b06f1](https://github.com/zextras/carbonio-files-ce/commit/02b06f17fc74acdb64a7e8468a1bcf3f60767955))
## [0.14.4](https://github.com/zextras/carbonio-files-ce/compare/v0.14.3...v0.14.4) (2024-12-12)

### Bug Fixes

* throw if storages fails on delete user event consumer ([#146](https://github.com/zextras/carbonio-files-ce/issues/146)) ([4c26e86](https://github.com/zextras/carbonio-files-ce/commit/4c26e861a8db780ec0808db598e868757be2d37f))
## [0.14.3](https://github.com/zextras/carbonio-files-ce/compare/v0.14.2...v0.14.3) (2024-12-10)

### Features

* emit confirmation event after deleting blobs on delete account requested ([#144](https://github.com/zextras/carbonio-files-ce/issues/144)) ([9895c46](https://github.com/zextras/carbonio-files-ce/commit/9895c462b7955ab18cd1b0e874ddb4299fdf0f54))
## [0.14.2](https://github.com/zextras/carbonio-files-ce/compare/v0.14.1...v0.14.2) (2024-11-28)

### Bug Fixes

* set 3600s timeout on public download ([#142](https://github.com/zextras/carbonio-files-ce/issues/142)) ([6976e9d](https://github.com/zextras/carbonio-files-ce/commit/6976e9dccb6fc367b67e8a284bb3901593c86d7a))
## [0.14.1](https://github.com/zextras/carbonio-files-ce/compare/v0.14.0...v0.14.1) (2024-11-27)
## [0.14.0](https://github.com/zextras/carbonio-files-ce/compare/v0.13.1...v0.14.0) (2024-11-18)

### Features

* add access code to link entity ([#131](https://github.com/zextras/carbonio-files-ce/issues/131)) ([aca2380](https://github.com/zextras/carbonio-files-ce/commit/aca2380b327032c5443b3674bac2a08c76cb6573))
* changed health check to live ([#132](https://github.com/zextras/carbonio-files-ce/issues/132)) ([650eb0d](https://github.com/zextras/carbonio-files-ce/commit/650eb0d74317a5c154b41a88d75b2cb1bc8f62ae))
* delete blobs from storages when user is deleted ([#120](https://github.com/zextras/carbonio-files-ce/issues/120)) ([c133328](https://github.com/zextras/carbonio-files-ce/commit/c133328cc2904b58a4760b30a3899f1a94e4d621))
* implement a service to delete old versions and when the max number version is decreased ([#111](https://github.com/zextras/carbonio-files-ce/issues/111)) ([3720409](https://github.com/zextras/carbonio-files-ce/commit/37204095d29a8576b86ccf4c53ff9da2ee694e83))
* let files manage account that have been closed using message broker ([#112](https://github.com/zextras/carbonio-files-ce/issues/112)) ([c071a76](https://github.com/zextras/carbonio-files-ce/commit/c071a762aeed18b1af64a4e6f96464f3196ebcfe))
* let user access a public folder link protected by an access code ([#133](https://github.com/zextras/carbonio-files-ce/issues/133)) ([0a1773d](https://github.com/zextras/carbonio-files-ce/commit/0a1773dacb7fc1fe0ae1fcf4021aa49a8e759cd2))

### Bug Fixes

* block access to files after public link is revoked ([#130](https://github.com/zextras/carbonio-files-ce/issues/130)) ([721ffec](https://github.com/zextras/carbonio-files-ce/commit/721ffec8ee6e0d94fc1209d635460be05d08da27))
* change how the key set of the page token is generated ([#137](https://github.com/zextras/carbonio-files-ce/issues/137)) ([8312e1a](https://github.com/zextras/carbonio-files-ce/commit/8312e1a663ad228a3c78b07190feddc2e302ee4e))
* filter out current file version in the deleteVersions operation ([#136](https://github.com/zextras/carbonio-files-ce/issues/136)) ([8918f5a](https://github.com/zextras/carbonio-files-ce/commit/8918f5aa1633b19614bb31c225011fe71cab8c4d))
* fixed rename node when moved to the folder where it already is ([#121](https://github.com/zextras/carbonio-files-ce/issues/121)) ([809bf63](https://github.com/zextras/carbonio-files-ce/commit/809bf630e8d5d0f40746c8f5501e056a7006b57b))
* initialize GraphQL instrumentations in a ChainedInstrumentation ([#134](https://github.com/zextras/carbonio-files-ce/issues/134)) ([5f2b44f](https://github.com/zextras/carbonio-files-ce/commit/5f2b44f3d267e942d31f41d61c66a44ae737286e))
* jenkins can now find pr title in checkout stage ([#135](https://github.com/zextras/carbonio-files-ce/issues/135)) ([bc87996](https://github.com/zextras/carbonio-files-ce/commit/bc879963f98e51418153d2fdf2519e08be60901a))
* make message broker optional ([#129](https://github.com/zextras/carbonio-files-ce/issues/129)) ([9cf57df](https://github.com/zextras/carbonio-files-ce/commit/9cf57df20bfb259f69b2e93567f9381d827b1516))
## [0.13.1](https://github.com/zextras/carbonio-files-ce/compare/v0.13.0...v0.13.1) (2024-09-11)
## [0.13.0](https://github.com/zextras/carbonio-files-ce/compare/v0.12.0...v0.13.0) (2024-08-27)

### Features

* refactor filesconfig to interface to facilitate server mocking for integration tests ([#116](https://github.com/zextras/carbonio-files-ce/issues/116)) ([cbc7be0](https://github.com/zextras/carbonio-files-ce/commit/cbc7be0a2fa1ba2e3ec43a60a308a3df56c349bd))

### Bug Fixes

* fixed clone as current coping only current version ([#117](https://github.com/zextras/carbonio-files-ce/issues/117)) ([97844c3](https://github.com/zextras/carbonio-files-ce/commit/97844c39f1e0adaa52c9d8616ab32fc5e7e1be70))
* rename node if moved in dir with another node with same name ([#113](https://github.com/zextras/carbonio-files-ce/issues/113)) ([199591e](https://github.com/zextras/carbonio-files-ce/commit/199591e7a754b0837de93b820dd08433659443cd))
* update rest.yaml openapi ([#118](https://github.com/zextras/carbonio-files-ce/issues/118)) ([121bfac](https://github.com/zextras/carbonio-files-ce/commit/121bfac780fd4e8d1dd9b3241dbc3f376cc711b5))
## [0.12.0](https://github.com/zextras/carbonio-files-ce/compare/v0.11.1...v0.12.0) (2024-06-17)

### Features

* add base structure for ITs and create findNodes ITs ([#99](https://github.com/zextras/carbonio-files-ce/issues/99)) ([7bac001](https://github.com/zextras/carbonio-files-ce/commit/7bac001f2590279162fea5e3d00de2e6d9348e12))
* expose docs-connector liveness in health API ([#104](https://github.com/zextras/carbonio-files-ce/issues/104)) ([f78baa8](https://github.com/zextras/carbonio-files-ce/commit/f78baa86232f3d7116d6a38c70eacddf6ec111e2))
* pass user's locale to preview when requesting a document preview ([#106](https://github.com/zextras/carbonio-files-ce/issues/106)) ([1781915](https://github.com/zextras/carbonio-files-ce/commit/17819154e4f195cfdf7b8c59480fd46dc4621374))
* refactor keyset builder and pagequery, add pagination ITs and keyset UTs ([#103](https://github.com/zextras/carbonio-files-ce/issues/103)) ([8727588](https://github.com/zextras/carbonio-files-ce/commit/87275881cef1bb51555b0ed468b7b9914cf36b88)), closes [NodeRepositoryEbean#getRealSortingsToApply](https://github.com/zextras/NodeRepositoryEbean/issues/getRealSortingsToApply) [NodeRepositoryEbean#doFind](https://github.com/zextras/NodeRepositoryEbean/issues/doFind)
* update files to use new user management sdk with returned user type ([#107](https://github.com/zextras/carbonio-files-ce/issues/107)) ([745c6ca](https://github.com/zextras/carbonio-files-ce/commit/745c6ca4e9fe8119510b2f2f1f35c0bde990d799))
* use new user management sdk with returned user status ([#105](https://github.com/zextras/carbonio-files-ce/issues/105)) ([f5e835b](https://github.com/zextras/carbonio-files-ce/commit/f5e835b1067a2022241148e4b8020996e75dedfa))

### Bug Fixes

* rename node if duplicate when restoring ([#108](https://github.com/zextras/carbonio-files-ce/issues/108)) ([5b1f8a1](https://github.com/zextras/carbonio-files-ce/commit/5b1f8a111ef5a7a53a912c0a288e54a1ba792a69))
* sorting by size with files of size 0 is now coherent ([#101](https://github.com/zextras/carbonio-files-ce/issues/101)) ([8386360](https://github.com/zextras/carbonio-files-ce/commit/8386360a72b5ce1b33466e17399d79a85183ffc6))
## [0.11.1](https://github.com/zextras/carbonio-files-ce/compare/v0.11.0...v0.11.1) (2024-04-12)

### Bug Fixes

* *.hcl: apply corrections to validate with hclfmt ([#93](https://github.com/zextras/carbonio-files-ce/issues/93)) ([26f5cba](https://github.com/zextras/carbonio-files-ce/commit/26f5cba8590ae0464b71269bda5cb2aa209ae1b2))
* support the upload to the mailbox of file with cyrillic filename ([#96](https://github.com/zextras/carbonio-files-ce/issues/96)) ([eaf0bde](https://github.com/zextras/carbonio-files-ce/commit/eaf0bdea2bb3d98efd88b0b2fcacf5fd11509ca3)), closes [URLEncorder#encode](https://github.com/zextras/URLEncorder/issues/encode)
## [0.11.0](https://github.com/zextras/carbonio-files-ce/compare/v0.10.1...v0.11.0) (2023-12-04)

### Features

* enable the creation of public link to folders ([#85](https://github.com/zextras/carbonio-files-ce/issues/85)) ([716ed1d](https://github.com/zextras/carbonio-files-ce/commit/716ed1de097ba40cecb9bd677a5ecfef58fbfc0c))
* expose public GraphQL schema ([#87](https://github.com/zextras/carbonio-files-ce/issues/87)) ([3ce9958](https://github.com/zextras/carbonio-files-ce/commit/3ce99583386c1f7e8c8d09b5fefef6fc6f254423))
* implement FindNodes API ([#89](https://github.com/zextras/carbonio-files-ce/issues/89)) ([0722048](https://github.com/zextras/carbonio-files-ce/commit/0722048bf039eca302e3966d225d06b1efb0c7e8)), closes [LinkRepository#hasNodeANotExpiredPublicLink](https://github.com/zextras/LinkRepository/issues/hasNodeANotExpiredPublicLink)
* implement GetPublicNode API ([#88](https://github.com/zextras/carbonio-files-ce/issues/88)) ([47f823d](https://github.com/zextras/carbonio-files-ce/commit/47f823df28741412abeebcf6418c543a3d59c207))
* implement public download API ([#90](https://github.com/zextras/carbonio-files-ce/issues/90)) ([41e9dab](https://github.com/zextras/carbonio-files-ce/commit/41e9dab54251258be15ed875e515b3af1c4cf64b)), closes [LinkRepository#getLinkByPublicLink](https://github.com/zextras/LinkRepository/issues/getLinkByPublicLink)
* increase the length of the hash of a public link to 32 characters ([#84](https://github.com/zextras/carbonio-files-ce/issues/84)) ([2505fc7](https://github.com/zextras/carbonio-files-ce/commit/2505fc7dc5cb10bc42644ebea21d4a3c9e6184ef))
* move to yap agent and add rhel9 support ([#82](https://github.com/zextras/carbonio-files-ce/issues/82)) ([3352cb3](https://github.com/zextras/carbonio-files-ce/commit/3352cb3366e46913c32201933f175205813c6269))
## [0.10.1](https://github.com/zextras/carbonio-files-ce/compare/v0.10.0...v0.10.1) (2023-09-01)

### Bug Fixes

* fix download routing allowing to download a specific file version ([#78](https://github.com/zextras/carbonio-files-ce/issues/78)) ([9428b06](https://github.com/zextras/carbonio-files-ce/commit/9428b068c64f856e948601434166254d47a8ad2d))
## [0.10.0](https://github.com/zextras/carbonio-files-ce/compare/v0.9.2...v0.10.0) (2023-07-06)

### Features

* integrate preview and thumbnail of GIF images ([#75](https://github.com/zextras/carbonio-files-ce/issues/75)) ([5f876f7](https://github.com/zextras/carbonio-files-ce/commit/5f876f7d1ffef8e5b031566bc983069e928f5005))
## [0.9.2](https://github.com/zextras/carbonio-files-ce/compare/v0.9.1...v0.9.2) (2023-06-20)

### Bug Fixes

* upload API should not return the version ([#70](https://github.com/zextras/carbonio-files-ce/issues/70)) ([c75b4ff](https://github.com/zextras/carbonio-files-ce/commit/c75b4ff1d419d65919ad59afd7ebb84c9a581047))
## [0.9.1](https://github.com/zextras/carbonio-files-ce/compare/v0.9.0...v0.9.1) (2023-06-19)
## [0.9.0](https://github.com/zextras/carbonio-files-ce/compare/v0.8.3...v0.9.0) (2023-05-30)

### Features

* reduce Hikari max pool size to 2 ([#63](https://github.com/zextras/carbonio-files-ce/issues/63)) ([4e1b9a9](https://github.com/zextras/carbonio-files-ce/commit/4e1b9a9999f4729c55f8bd2f8460131a68d7050f))
## [0.8.3](https://github.com/zextras/carbonio-files-ce/compare/v0.8.2...v0.8.3) (2023-05-25)

### Performance Improvements

* replace PGSimpleDataSource with HikariDataSource ([#61](https://github.com/zextras/carbonio-files-ce/issues/61)) ([269895d](https://github.com/zextras/carbonio-files-ce/commit/269895de9009672aef1124efcb2cf4097717f474))
## [0.8.2](https://github.com/zextras/carbonio-files-ce/compare/v0.8.1...v0.8.2) (2023-04-27)

### Bug Fixes

* fix GetNode when a specific node version is given ([#58](https://github.com/zextras/carbonio-files-ce/issues/58)) ([13cc3a0](https://github.com/zextras/carbonio-files-ce/commit/13cc3a079643817b466d2f42a9561ddd65ba43cf))
## [0.8.1](https://github.com/zextras/carbonio-files-ce/compare/v0.8.0...v0.8.1) (2023-03-31)

### Bug Fixes

* FILES-563 - Move jar in /usr/share/carbonio folder ([#51](https://github.com/zextras/carbonio-files-ce/issues/51)) ([4fb1356](https://github.com/zextras/carbonio-files-ce/commit/4fb13563189999dd1d4a20c628c36836ec562c70))
* FILES-618 add ExceptionHandler at the end of each endpoint pipeline ([#56](https://github.com/zextras/carbonio-files-ce/issues/56)) ([c134a5e](https://github.com/zextras/carbonio-files-ce/commit/c134a5ec6c43cc03f13e4ed1b0030d090dae2d04))
## [0.8.0](https://github.com/zextras/carbonio-files-ce/compare/v0.7.0...v0.8.0) (2023-02-03)

### Features

* FILES-89 - Add onwer_id and type parameters in findNodes ([#52](https://github.com/zextras/carbonio-files-ce/issues/52)) ([75111c7](https://github.com/zextras/carbonio-files-ce/commit/75111c7144fa6340160054e95bf55c949112c490))

### Bug Fixes

* FILES-572 - Fix tags definition in carbonio-files.hcl ([#53](https://github.com/zextras/carbonio-files-ce/issues/53)) ([eea76b6](https://github.com/zextras/carbonio-files-ce/commit/eea76b604052c2037949286b1e46f57759dd1f87))
## [0.7.0](https://github.com/zextras/carbonio-files-ce/compare/v0.6.0...v0.7.0) (2022-11-24)

### Features

* FILES-462 - Setup project to run tests ([#45](https://github.com/zextras/carbonio-files-ce/issues/45)) ([9de8a7d](https://github.com/zextras/carbonio-files-ce/commit/9de8a7d26d65eff4e8f35251d2457a11687415df))
* FILES-484 - Add cache-control and etag headers in Preview responses ([#46](https://github.com/zextras/carbonio-files-ce/issues/46)) ([577c5d4](https://github.com/zextras/carbonio-files-ce/commit/577c5d4f8c6fe125225571cb257304fa48c6dbbb))
## [0.6.0](https://github.com/zextras/carbonio-files-ce/compare/v0.5.0...v0.6.0) (2022-10-24)

### Features

* AR-62 - Add /metrics endpoint for monitoring with Prometheus ([641a139](https://github.com/zextras/carbonio-files-ce/commit/641a1390efeebba66b2251f759aa419fc6d49353))

### Bug Fixes

* FILES-436 - Fix GetPath API to return nodes ordered correctly ([#41](https://github.com/zextras/carbonio-files-ce/issues/41)) ([24283d5](https://github.com/zextras/carbonio-files-ce/commit/24283d5d1ee2e804dd365c93d6e43ae8dfde11d0))
* FILES-437 - Fix timeout of copy API when file to copy is large ([#42](https://github.com/zextras/carbonio-files-ce/issues/42)) ([b99eb8c](https://github.com/zextras/carbonio-files-ce/commit/b99eb8cccc4ecc3bd2b903ec1c709a3a069bcbcd))
## [0.5.0](https://github.com/zextras/carbonio-files-ce/compare/v0.4.1...v0.5.0) (2022-09-02)

### Features

* FILES-299 - Implement Collaboration link ([#39](https://github.com/zextras/carbonio-files-ce/issues/39)) ([347a57d](https://github.com/zextras/carbonio-files-ce/commit/347a57dd5860b02ea24dda266531fe1ff842ebbc)), closes [#35](https://github.com/zextras/carbonio-files-ce/issues/35) [#37](https://github.com/zextras/carbonio-files-ce/issues/37) [#38](https://github.com/zextras/carbonio-files-ce/issues/38)
## [0.4.1](https://github.com/zextras/carbonio-files-ce/compare/v0.4.0...v0.4.1) (2022-08-05)

### Bug Fixes

* FILES-354 - Fix RootId null when root is not LOCAL_ROOT ([#33](https://github.com/zextras/carbonio-files-ce/issues/33)) ([86d851e](https://github.com/zextras/carbonio-files-ce/commit/86d851e8a2f1593063c5e5d83dd222c9ac537e42))
## [0.4.0](https://github.com/zextras/carbonio-files-ce/compare/v0.3.7...v0.4.0) (2022-07-22)

### Features

* FILES-194 - Reduce response latencies of GraphQL APIs ([#30](https://github.com/zextras/carbonio-files-ce/issues/30)) ([f15a2c7](https://github.com/zextras/carbonio-files-ce/commit/f15a2c7bee9407333bdcd81cec11f8c3e3d18502))
* FILES-290 - Implement getAccountsByEmail API ([#25](https://github.com/zextras/carbonio-files-ce/issues/25)) ([aea3f0a](https://github.com/zextras/carbonio-files-ce/commit/aea3f0a9b36fd469896d036b8ec627c97e5b6ea3))
* FILES-320 - Align pom version to package version ([#26](https://github.com/zextras/carbonio-files-ce/issues/26)) ([cdc591f](https://github.com/zextras/carbonio-files-ce/commit/cdc591fc43c992533b68697e08c569fc31e21a2d))
* FILES-325 - Improve how the database schema is initialized ([#29](https://github.com/zextras/carbonio-files-ce/issues/29)) ([254347a](https://github.com/zextras/carbonio-files-ce/commit/254347a79495e5ea476fbe80db6a78f1486c4d24))

### Bug Fixes

* FILES-311 - Fix download timeout on large files ([#31](https://github.com/zextras/carbonio-files-ce/issues/31)) ([0e9f5c3](https://github.com/zextras/carbonio-files-ce/commit/0e9f5c33347d70444b9eb244923c9cf0062b482e))
* FILES-354 - Fix GetChildren API called on LOCAL_ROOT folder ([#32](https://github.com/zextras/carbonio-files-ce/issues/32)) ([a4f4fc4](https://github.com/zextras/carbonio-files-ce/commit/a4f4fc450e822b81fe58de584eaceb0f23b19dda))
## [0.3.7](https://github.com/zextras/carbonio-files-ce/compare/v0.3.6...v0.3.7) (2022-06-09)

### Features

* FILES-283 - Propagate boot log configuration ([#21](https://github.com/zextras/carbonio-files-ce/issues/21)) ([fe1ac2e](https://github.com/zextras/carbonio-files-ce/commit/fe1ac2e6e47d0d5c3e131daee3e24e8553471936))
* FILES-33 - Implement max version logic ([#11](https://github.com/zextras/carbonio-files-ce/issues/11)) ([60f0465](https://github.com/zextras/carbonio-files-ce/commit/60f04650b39f27df228f027eb6da996f2645a28f))

### Bug Fixes

* CopyNodes does not maintain the name when there are conflicts ([#23](https://github.com/zextras/carbonio-files-ce/issues/23)) ([8a50cff](https://github.com/zextras/carbonio-files-ce/commit/8a50cff75b7b11d2cbeaacf82e1e24a2b693d508))
* FILES-114 - Delete shared nodes ([#22](https://github.com/zextras/carbonio-files-ce/issues/22)) ([8c4a552](https://github.com/zextras/carbonio-files-ce/commit/8c4a5529349c74d90c2530a17b15b7406fbba6ce))
* Fix Jenkinsfile in order to upload the rpm correctly ([#24](https://github.com/zextras/carbonio-files-ce/issues/24)) ([814c90f](https://github.com/zextras/carbonio-files-ce/commit/814c90f63d581b04dda69131ab8e3148ccb8ed3d))
## [0.3.6](https://github.com/zextras/carbonio-files-ce/compare/v0.3.5...v0.3.6) (2022-05-24)

### Bug Fixes

* Return 404 when the preview is not available ([#20](https://github.com/zextras/carbonio-files-ce/issues/20)) ([4a07f43](https://github.com/zextras/carbonio-files-ce/commit/4a07f43502f77c6a4b76f5a2c2a162343750a367))
## [0.3.5](https://github.com/zextras/carbonio-files-ce/compare/v0.3.4...v0.3.5) (2022-05-24)

### Bug Fixes

* FILES-27 FILES-71 FILES-115 - Fix findNodes and sorting ([#17](https://github.com/zextras/carbonio-files-ce/issues/17)) ([b28eb45](https://github.com/zextras/carbonio-files-ce/commit/b28eb452de303a2eb3490a39682a1f97705b0ac8))
* Fix the creation of ancestor_ids during the copy operation ([#18](https://github.com/zextras/carbonio-files-ce/issues/18)) ([5dad817](https://github.com/zextras/carbonio-files-ce/commit/5dad817965aed1725130a9b845e12751c97abe6e))
## [0.3.4](https://github.com/zextras/carbonio-files-ce/compare/v0.3.3...v0.3.4) (2022-05-16)

### Features

* FILES-198 - Implement health, health/ready, health/live endpoints ([#14](https://github.com/zextras/carbonio-files-ce/issues/14)) ([1e9e7a6](https://github.com/zextras/carbonio-files-ce/commit/1e9e7a679a50cda8f5374469c7ccbf40f702417d))

### Bug Fixes

* FILES-112 - Avoid creating folder with the same name ([#13](https://github.com/zextras/carbonio-files-ce/issues/13)) ([a71ae9b](https://github.com/zextras/carbonio-files-ce/commit/a71ae9bb7e13780a5d65b1e564106e498b2df99d))
* FILES-229 - Fix NullPointer when a search is paginated ([#15](https://github.com/zextras/carbonio-files-ce/issues/15)) ([d459338](https://github.com/zextras/carbonio-files-ce/commit/d459338d7e6fa779a07c94b6e593f7bea6dae088))
* FILES-263 - Fix preview/thumbnail requests in bulk ([#12](https://github.com/zextras/carbonio-files-ce/issues/12)) ([92ab8c3](https://github.com/zextras/carbonio-files-ce/commit/92ab8c3d30b4a431329a1eb6674b4f1825330768))
* In health/ready/ API, replace log level from debug to info ([#16](https://github.com/zextras/carbonio-files-ce/issues/16)) ([bd21c99](https://github.com/zextras/carbonio-files-ce/commit/bd21c994f64bf60620a99051fbf5bcbf8b4e9435))
## [0.3.3](https://github.com/zextras/carbonio-files-ce/compare/v0.3.2...v0.3.3) (2022-05-10)

### Features

* Add intentions for mailbox and videoserver-recorder ([#10](https://github.com/zextras/carbonio-files-ce/issues/10)) ([076ff51](https://github.com/zextras/carbonio-files-ce/commit/076ff516684683e37f82a3ce01b7aaa2aad9f01d))
* FILES-247 - Expose service discover configs with GraphQL ([#9](https://github.com/zextras/carbonio-files-ce/issues/9)) ([55a94b5](https://github.com/zextras/carbonio-files-ce/commit/55a94b5723f30736414ffb9a901a02800c1aa390))
## [0.3.2](https://github.com/zextras/carbonio-files-ce/compare/v0.3.1...v0.3.2) (2022-05-09)

### Features

* FILES-220 - Implement document preview api ([#7](https://github.com/zextras/carbonio-files-ce/issues/7)) ([86791d5](https://github.com/zextras/carbonio-files-ce/commit/86791d519fc91b8b1ca9a11d0069834db3db6916))

### Bug Fixes

* Pass the right owner id to the preview SKD ([#8](https://github.com/zextras/carbonio-files-ce/issues/8)) ([996a3dd](https://github.com/zextras/carbonio-files-ce/commit/996a3dd3804051a84a93b4e095fc9184c423917d))
## [0.3.1](https://github.com/zextras/carbonio-files-ce/compare/v0.3.0...v0.3.1) (2022-04-13)

### Features

* FILES-187 - Update carbonio-preview-sdk ([7455ac2](https://github.com/zextras/carbonio-files-ce/commit/7455ac25538763559d22cf9c43773134a60b8808))
* FILES-196 - Implement upload-to API to push files in mailbox store ([#4](https://github.com/zextras/carbonio-files-ce/issues/4)) ([b8cd9f4](https://github.com/zextras/carbonio-files-ce/commit/b8cd9f487714dc1855ca85d91b0b70aee0ee8d92))
* FILES-214 - Update carbonio-storages-sdk ([6e8d1be](https://github.com/zextras/carbonio-files-ce/commit/6e8d1bea82ed54b95b18bc642c404bdddd3c1040))

### Bug Fixes

* FILES-155 - Update config, fix user-management-sdk version ([22a4b4b](https://github.com/zextras/carbonio-files-ce/commit/22a4b4b7789e24229a32a33c1ded3fe842a92f48))
* FILES-218 - setup log configuration ([#5](https://github.com/zextras/carbonio-files-ce/issues/5)) ([0f44f50](https://github.com/zextras/carbonio-files-ce/commit/0f44f50049aae986a022fb4b1215ab3c1da5acb5))
* FILES-221 - Fix download and public link permissions ([#6](https://github.com/zextras/carbonio-files-ce/issues/6)) ([6bb62fc](https://github.com/zextras/carbonio-files-ce/commit/6bb62fc1347abdc3a36ada7f679259d85b964e5d))
* Refactor how the config is loaded ([f4287ae](https://github.com/zextras/carbonio-files-ce/commit/f4287ae911e21bae8014756ac18b4632ab77c7ef))
## [0.2.0](https://github.com/zextras/carbonio-files-ce/compare/71d8bcd698bd978138d61129938c9bb640123232...v0.2.0) (2022-03-23)

### Features

* carbonio release ([71d8bcd](https://github.com/zextras/carbonio-files-ce/commit/71d8bcd698bd978138d61129938c9bb640123232))
