```shell
docker run --interactive --tty --rm --name certbot \
--volume "$(pwd)/etc/letsencrypt:/etc/letsencrypt" \
certbot/certbot \
certonly \
--manual \
--agree-tos \
--force-renewal \
--preferred-challenges dns \
--email letsencrypt@4sitive.com \
--domains '4sitive.com' \
--domains '*.4sitive.com' \
--staging
```
```shell
openssl pkcs12 -export \
-out keystore.pfx \
-inkey privkey1.pem \
-in cert1.pem \
-certfile chain1.pem \
-passout pass:changeit
```