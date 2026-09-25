# Kuikly release-set inputs

`kuikly-predecessors.candidate.json` is an intentionally empty source-review
fixture. PR jobs still assemble the exact dependency graph, but every
Raft-required original coordinate remains visibly `missing` until a protected
receipt proves that exact coordinate and byte set. A suffixed successor is not
accepted as a substitute for an original KBA coordinate.

It is not publication authority. The protected publication job receives the
complete predecessor receipt as an environment secret, verifies its SHA-256,
and reassembles the release from the exact tagged source. That protected
receipt must cover every Raft-required dependency discovered in the generated
POM and Gradle module metadata, including the Tencent Compose and original KBA
closures owned by the predecessor lanes. Missing coordinates keep the release
manifest non-publishable.

Every `coordinates` entry is keyed by its exact GAV and must repeat that GAV in
`coordinate`, carry `status: verified`, bind a 64-hex `manifestSha256`, and
carry `publicReadbackState: verified`. A source/tag receipt without independent
public byte readback is deliberately insufficient.

The product byte set includes every immutable version-directory primary plus
all four required `.md5`, `.sha1`, `.sha256`, and `.sha512` companions. A
detached `.asc` is optional, but if a producer emits one it and all four of its
checksums enter the same byte closure. Only artifact-level mutable
`maven-metadata.xml` and its companions are excluded. Checksum bodies are
recomputed against their direct object before publication and public byte
readback are frozen. The manifest reports 37 product seeds
separately from its complete primary/checksum/signature physical-file counts.

The candidate and protected assemblies run two fresh-cache POM consumers.
Gradle 7.6.3 disables Gradle-metadata redirection and must resolve the complete
top-level `compose-android` runtime graph from Maven POMs and normal artifacts.
Maven 3.8.7 preserves its raw terminal result and proves the two Kuikly owner
AAR edges separately: both exact candidate AARs must be downloaded, with no
owner JAR request, fallback, byte drift, or unresolved owner coordinate. A
nonzero Maven result is admissible only after that owner closure and is labeled
`OWNER_EDGE_CLOSED / EXTERNAL_TRANSITIVE_DIAGNOSTIC` with the complete external
unresolved set; it is never reported as a full Maven-graph pass. Neither
consumer adds a type, classifier, exclusion, direct dependency, dependency
management rule, or rewritten third-party POM.

The workflow never obtains a token on pull requests or staging3 pushes.
The protected manual publication job uses one repository-scoped,
long-lived `RAFT_ARTIFACTS_PUBLISH_TOKEN`, just like an ordinary Maven
repository credential. It performs normal authenticated PUTs. Existing exact
bytes are reused, a byte mismatch fails without overwrite, and an interrupted
run retries only the missing files with the same immutable version. There are
no per-release credentials, claim leases, or token receipts in this contract.

After every product file is anonymously read back and its complete set digest
is verified, the completion locator is written last and read back byte for
byte. It is not a 38th product seed. Consumers may use only that completed
manifest; a partial product prefix is not an admitted release. GitHub OIDC
trusted publishing can later replace the long-lived secret without changing
these Maven bytes or the manifest-last consumer contract.
