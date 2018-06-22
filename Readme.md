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

Put this in a shell like jmiddle.sh and then call goreplay via

```bash
sudo ./goreplay --input-raw :8989 --middleware "jmiddle.sh" --output-http-track-response --input-raw-track-response --output-http "http://somewherelese" --prettify-http --output-http-timeout 60s
```

As the output is not stdout but stderr (goreplay is using stdout) you need
to use `2> output.log` to pipe the output into a file.

## License

Apache License 2.0
