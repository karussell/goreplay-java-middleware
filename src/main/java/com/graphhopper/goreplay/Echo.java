package com.graphhopper.goreplay;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;


public class Echo {

    public static void main(String[] args) {
        if (args != null) {
            for (String arg : args) {
                System.out.println(arg);
            }
        }

        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        String line;
        log("start");

        // create some kind of unbounded blocking queue
        final LinkedBlockingQueue<Message> queue = new LinkedBlockingQueue<Message>();
        // messages indexed via message.id
        final Map<String, List<Message>> allMessages = new ConcurrentHashMap<String, List<Message>>();
        // jobIds (from replays) indexed via jobId (from original request)
        final Map<String, String> replayJobIds = new ConcurrentHashMap<String, String>();
        new Thread() {
            @Override
            public void run() {
                try {
                    while (true) {
                        Message message = queue.take();
                        String path = message.getPath();
                        int solutionIndex = path.indexOf("/solution/");

                        if (message.getTypeInfo().equals("request") && solutionIndex >= 0) {
                            int fromIndex = solutionIndex + 10;
                            int toIndex = path.indexOf("?", fromIndex);
                            if (toIndex < 0)
                                toIndex = path.length();

                            if (fromIndex >= 0 && toIndex > 0) {
                                String jobId = path.substring(fromIndex, toIndex);
                                String replayJobId = replayJobIds.get(jobId);
                                if (replayJobId == null) {
                                    // re-queue if jobId not yet there but wait ~5sec maximum
                                    message.enqueued++;
                                    if (message.enqueued < 50) {
                                        // now that we are decoupled from writing we can wait a bit
                                        Thread.sleep(100);
                                        queue.offer(message);
                                    } else {
                                        log("didn't find replay job_id for " + message);
                                    }
                                    continue;
                                }

                                String newPath = path.substring(0, solutionIndex) + "/solution/" + replayJobIds.get(jobId) + path.substring(toIndex);
                                // log("setPath " + path + " vs " + newPath);
                                message.setPath(newPath);
                            }
                        } else if (message.getTypeInfo().equals("response")) {
                            // log("response: " + message);

                        } else if (message.getTypeInfo().equals("replay")) {
                            // should contain 2 messages: request and response
                            List<Message> messages = allMessages.get(message.id);
                            if (messages != null && messages.size() == 2) {
                                Message request = messages.get(0);
                                Message response = messages.get(1);
                                // order can be mixed up due to re-queuing
                                if (request.getTypeInfo().equals("response")) {
                                    request = messages.get(1);
                                    response = messages.get(0);
                                }
                                if (!message.status.equals(response.status)) {
                                    messages.add(message);
                                    log("status different. replay: " + message.status + " vs response: " + response.status + " for " + messages);
                                    if (!message.status.contains("200")) {
                                        log(request.getPath() + " -> \n" + request.getBody());
                                    }
                                } else if (message.getJobId().length() > 0) {
                                    // log("set response job_id: " + response.getJobId() + " to replay job_id: " + message.getJobId());
                                    // TODO memory leak if used for many requests
                                    replayJobIds.put(response.getJobId(), message.getJobId());
                                }
                            }
                        }

                        List<Message> indexedMessages = allMessages.get(message.id);
                        if (indexedMessages == null)
                            allMessages.put(message.id, new ArrayList<Message>(Arrays.asList(message)));
                        else
                            indexedMessages.add(message);
                        System.out.println(message.toHexMessage());
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }.start();

        try {
            while ((line = stdin.readLine()) != null) {
                queue.offer(new Message(decodeHexString(line)));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String decodeHexString(String s) throws DecoderException {
        return new String(Hex.decodeHex(s.toCharArray()));
    }

    static void log(String s) {
        System.err.println(new Date() + " " + s);
    }

// the raw parameter contains the following message (a replay in this case)
//3 047ec018ab89a2ddbc2e4ec21696f49943e93297 1529276174044003791 41483702\r\n
//HTTP/1.1 200 OK\r\n
//Date: Sun, 17 Jun 2018 22:56:14 GMT\r\n
//X-GH-Took: 14.0\r\n
//X-GH-Waiting: 0\r\n
//Content-Type: application/json\r\n
//Vary: Accept-Encoding\r\n
//Content-Length: 105\r\n
//\r\n
//{"distances":[[9010]],"times":[[708]],"info":{"copyrights":["GraphHopper","OpenStreetMap contributors"]}}

// for a POST request
//1 047ec018ab89a2ddbc2e4ec21696f49943e93297\r\n
//POST / HTTP/1.1\r\n
//...

    static String parseJobId(String body) {
        // TODO would be a bit easier with a json lib, but for now it is sufficient
        if (body.startsWith("{") && body.contains("\"job_id\"")) {
            int index = body.indexOf("\"", body.indexOf(":"));
            int toIndex = body.indexOf("\"", index + 1);
            if (index >= 0 && toIndex > 0) {
                String jobId = body.substring(index + 1, toIndex);
                return jobId;
            }
        }
        return "";
    }

    static class Message {
        final String[] reqString;
        final String type;
        final String typeInfo;
        final String id;
        int enqueued = 0;
        String jobId = "";
        String status = "";
        String path = "";

        public Message(String raw) {
            reqString = raw.split("\n");
            if (reqString.length < 2)
                throw new IllegalStateException("Illegal message format " + raw);

            type = reqString[0].split(" ")[0];
            id = reqString[0].split(" ")[1];
            // [2] => time (https://github.com/buger/goreplay/wiki/Middleware)
            // [3] => latency

            if ("3".equals(type)) {
                typeInfo = "replay";
                status = reqString[1].split(" ")[1];
                jobId = parseJobId(getBody());
            } else if ("2".equals(type)) {
                typeInfo = "response";
                status = reqString[1].split(" ")[1];
                jobId = parseJobId(getBody());
            } else if ("1".equals(type)) {
                typeInfo = "request";
                // GET /xy
                path = reqString[1].split(" ")[1];
            } else
                throw new IllegalStateException("unknown message type " + type);
        }

        public String getTypeInfo() {
            return typeInfo;
        }

        public void setPath(String path) {
            if (type.equals("1")) {
                this.path = path;
                String reqLine = reqString[1];
                int to1Index = reqLine.indexOf(" ");
                int to2Index = reqLine.indexOf(" ", to1Index + 1);
                if (to1Index < 0 || to2Index < 0)
                    throw new IllegalStateException("Wrong request format " + reqLine + " for message " + id);
                reqString[1] = reqLine.substring(0, to1Index + 1) + path + reqLine.substring(to2Index);
            } else {
                throw new IllegalArgumentException("URL only valid for request but was " + typeInfo);
            }
        }

        public String getPath() {
            return path;
        }

        public String getJobId() {
            return jobId;
        }

        public String getBody() {
            return reqString[reqString.length - 1];
        }

        @Override
        public String toString() {
            String str = typeInfo + " " + id + " " + path;
            if (status.length() > 0)
                str += " (status " + status + ")";
            return str;
        }

        public String toHexMessage() {
            return new String(Hex.encodeHex(toMessage().getBytes()));
        }

        public String toMessage() {
            StringBuffer sb = new StringBuffer();
            for (String msgPart : reqString) {
                sb.append(msgPart + "\n");
            }
            return sb.toString();
        }
    }
}
