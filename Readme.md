# A stateful middleware for goreplay in Java

See the wiki for a middleware in goreplay: https://github.com/buger/goreplay/wiki/Middleware

This middleware handles receiving and responding in a separate thread to
make it possible to re-enqueue a message in order to let it wait for a
stateful previous request.

Assume you do one request that responds with a token or in our case job_id.
Then the next request must contain this job_id and the replay cannot use the
same job_id as the original response.

Discussed a bit in https://github.com/buger/goreplay/issues/154

## Build
mvn clean install

## Usage
java -jar target/testing-goreplay-*.jar

## License

Apache License 2.0
