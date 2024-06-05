# ton-airgap-client

This is an online part of [TON Air Gap Wallet](https://github.com/ton-offline-storage), supposed to work on an online android device

It broadcasts transactions to the network, displays balance and history. To create and sign transactions, use [Offline client](https://github.com/ton-offline-storage/ton-offline-client/tree/main)

## Usage

Download latest APK [release](https://github.com/ton-offline-storage/ton-airgap-client/releases)(`ton-airgap-client.apk`) and install on android device

## Screenshots
<table>
<tr></tr>
<tr>
    <td><img src="/screenshots/start.png"></td>
    <td><img src="/screenshots/transfer.png"></td>
   <td><img src="/screenshots/wallet_list.png"></td>
    <td><img src="/screenshots/account.png"></td>
</tr>
</table>

## Back-end

Application relies on some established TON back-end services:

- Free public liteservers from the public [configuration](https://ton.org/global-config.json) - for core functionality, sending transactions, receiving blockchain data
- https://toncenter.com/ - for transaction fee estimation
- https://tonapi.io/ - for displaying TON market price

## Libraries
- [ton-kotlin](https://github.com/ton-community/ton-kotlin)
- [Code-Scanner](https://github.com/yuriy-budiyev/code-scanner)
- [identikon](https://github.com/thibseisel/identikon)
- [Volley](https://google.github.io/volley/)
