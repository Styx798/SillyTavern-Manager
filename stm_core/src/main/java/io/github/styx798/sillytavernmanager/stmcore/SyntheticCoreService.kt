package io.github.styx798.sillytavernmanager.stmcore

internal object SyntheticCoreService {
    const val HEALTH_PATH = "/health"
    const val HEALTH_BODY =
        "{\"status\":\"ok\",\"component\":\"stm-core\",\"engine\":\"feather\",\"version\":\"0.1.0\"}"

    val script: String =
        """
        'use strict';
        const http = require('http');
        const healthBody = ${jsString(HEALTH_BODY)};
        const state = globalThis.__stmCore = {
          server: null,
          port: 0,
          closed: false,
          error: '',
          requestCount: 0,
          lastRequest: '',
          controlTimer: null,
        };
        state.controlTimer = setInterval(() => {}, 50);
        const server = http.createServer((request, response) => {
          state.requestCount += 1;
          state.lastRequest = String(request.method) + ' ' + String(request.url);
          try {
            if (request.method === 'GET' && request.url === '$HEALTH_PATH') {
              response.writeHead(200, {
                'Content-Type': 'application/json; charset=utf-8',
                'Content-Length': Buffer.byteLength(healthBody),
              });
              response.end(healthBody);
              return;
            }
            const notFound = 'Not Found';
            response.writeHead(404, {
              'Content-Type': 'text/plain; charset=utf-8',
              'Content-Length': Buffer.byteLength(notFound),
            });
            response.end(notFound);
          } catch (error) {
            state.error = String(error && (error.stack || error.message || error));
            response.destroy();
          }
        });
        state.server = server;
        server.on('error', (error) => {
          state.error = String(error && (error.stack || error.message || error));
        });
        server.on('clientError', (error) => {
          state.error = 'clientError: ' + String(error && (error.stack || error.message || error));
        });
        process.on('uncaughtException', (error) => {
          state.error = 'uncaughtException: ' + String(error && (error.stack || error.message || error));
        });
        process.on('unhandledRejection', (error) => {
          state.error = 'unhandledRejection: ' + String(error && (error.stack || error.message || error));
        });
        server.listen(0, '127.0.0.1', () => {
          const address = server.address();
          state.port = address && typeof address === 'object' ? address.port : 0;
        });
        """.trimIndent()

    private fun jsString(value: String): String =
        buildString(value.length + 2) {
            append('\'')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '\'' -> append("\\'")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(character)
                }
            }
            append('\'')
        }
}
