# SPDX-License-Identifier: AGPL-3.0-only
FROM eclipse-temurin:25-jre
RUN install -d -o root -g root -m 0755 /opt/intruvia /var/lib/intruvia \
	&& install -d -o 10001 -g 10001 -m 0700 /var/lib/intruvia/runtime /var/log/intruvia
COPY --chown=0:0 lib/ /opt/intruvia/lib/
COPY --chown=0:0 intruvia-app.jar /opt/intruvia/intruvia-app.jar
RUN chmod -R a-w,a+rX /opt/intruvia
COPY --chown=0:0 --chmod=0555 reset-viewer-password.sh /usr/local/bin/intruvia-reset-viewer-password
WORKDIR /opt/intruvia
ENV INTRUVIA_BIND_ADDRESS=0.0.0.0
USER 10001:10001
ENTRYPOINT ["sh", "-c", "umask 077; exec java -Dintruvia.log.dir=/var/log/intruvia -cp '/opt/intruvia/intruvia-app.jar:/opt/intruvia/lib/*' li.intruvia.app.DeploymentEntrypoint /var/lib/intruvia/runtime /opt/intruvia/templates 8080 \"$@\"", "intruvia"]
CMD ["serve"]