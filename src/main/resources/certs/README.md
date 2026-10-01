# TLS certificates

Place the official **Russian Trusted Root CA** (and sub CA) here as a single PEM file:

    russian-trusted-ca.pem

Download from the official source (Минцифры / gosuslugi): https://gosuslugi.ru/crt
Concatenate the root and sub-CA certificates into one PEM (multiple `BEGIN CERTIFICATE` blocks are fine).

It is loaded **only** by the T-Bank client (`TbankTls`) to trust `toplivo.tbank.ru`, whose chain is
issued by the Russian Trusted Root CA (not in default trust stores). If the file is absent, the app
still starts; T-Bank calls just fail TLS and the T-Bank column shows "·".
