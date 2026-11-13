# SPDX-License-Identifier: AGPL-3.0-only
FROM eclipse-temurin:25-jre
RUN install -d -o 10001 -g 10001 -m 0700 /opt/intruvia /var/lib/intruvia/runtime /var/log/intruvia
COPY --chown=10001:10001 lib/ /opt/intruvia/lib/
COPY --chown=10001:10001 intruvia-app.jar /opt/intruvia/intruvia-app.jar
WORKDIR /opt/intruvia
ENV INTRUVIA_BIND_ADDRESS=0.0.0.0
USER 10001:10001
ENTRYPOINT ["sh", "-c", "umask 077; exec java -Dintruvia.log.dir=/var/log/intruvia -cp '/opt/intruvia/intruvia-app.jar:/opt/intruvia/lib/*' li.intruvia.app.DeploymentEntrypoint /var/lib/intruvia/runtime /opt/intruvia/templates 8080 \"$@\"", "intruvia"]
CMD ["serve"]