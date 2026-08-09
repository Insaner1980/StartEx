# Third-party notices

## Trust Wallet Core

- Package: `com.trustwallet:wallet-core:4.7.3`
- Vendored artifact: `wallet-core-4.7.3.aar`
- Source: https://github.com/trustwallet/wallet-core/tree/4.7.3
- SHA-256: `0082319df49f2cb0641fc06f7a5633e0fb3e2caadccdb1c4ba58626315149708`
- License: Apache License 2.0 (https://github.com/trustwallet/wallet-core/blob/4.7.3/LICENSE)

The Java bindings release native `HDWallet` and `PrivateKey` handles through phantom-reference cleanup after garbage collection; they do not expose deterministic close operations. StartEx clears the JVM entropy and private-key byte arrays it owns in `finally` blocks, but cannot guarantee when native secret memory is reclaimed or wiped.
