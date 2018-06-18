package com.graphhopper.goreplay;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;


public class Echo {
    public static String decodeHexString(String s) throws DecoderException {
        return new String(Hex.decodeHex(s.toCharArray()));
    }

    public static String transformHTTPMessage(String req) {
        Message msg = new Message(req);
        return msg.toMessage();
        // System.err.println("REQUEST\n" + req +"\nEND\n\n");
//        String[] reqString = req.split("\n");
//        if (reqString.length == 0)
//            return req;
//
//        String type = reqString[0].split(" ")[0];
//        String ID = reqString[0].split(" ")[1];
//        if ("3".equals(type)) {
//            System.err.println("REPLAY   " + ID + " " + reqString[1]);
//            // TODO compare status
//        } else if ("2".equals(type)) {
//            System.err.println("RESPONSE " + ID + " " + reqString[1]);
//            // TODO grab job_id from response JSON
//            for (int i = 0; i < reqString.length; i++) {
//                if (reqString[i].length() < 2 && i + 1 < reqString.length) {
//                    // TODO System.err.println(reqString[i+1]);
//                    break;
//                }
//            }
//        } else if ("1".equals(type)) {
//            System.err.println("REQUEST  " + ID + " " + reqString[1]);
//            // TODO if URL contains solution, then put into a queue e.g. on separate thread (+ println) if jobId is not yet in the map
//            // TODO if jobId was found => rewrite URL
//        } else {
//            throw new IllegalStateException("UNKNOWN  " + ID + " " + reqString[1]);
//        }
//        return req;
    }

    public static void main(String[] args) throws DecoderException {
        if (args != null) {
            for (String arg : args) {
                System.out.println(arg);
            }

        }

        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        String line;
        System.err.println("start");

        // create some kind of unbounded blocking queue
        final LinkedBlockingQueue<Message> queue = new LinkedBlockingQueue<Message>();
        // messages indexed via message.id
        final Map<String, List<Message>> allMessages = new ConcurrentHashMap<String, List<Message>>();
        // jobIds (from replays) indexed via jobId (from original request)
        final Map<String, String> jobIds = new ConcurrentHashMap<String, String>();
        new Thread() {
            @Override
            public void run() {
                try {
                    while (true) {
                        Message message = queue.take();
                        String path = message.getPath();
                        int solutionIndex = path.indexOf("/solution/");

                        if (message.getType().equals("request") && solutionIndex >= 0) {
                            int fromIndex = solutionIndex + 10;
                            int toIndex = path.indexOf("/", fromIndex);
                            if (fromIndex >= 0 && toIndex > 0) {
                                String jobId = path.substring(fromIndex, toIndex);
                                String otherJobId = jobIds.get(jobId);
                                if (otherJobId == null) {
                                    // re-queue if jobId not yet there but don't do this infinite
                                    message.enqueued++;
                                    if (message.enqueued < 3) {
                                        queue.offer(message);
                                    } else {
                                        System.err.println("did not found job id for " + message);
                                    }
                                    continue;
                                }

                                String newPath = path.substring(0, solutionIndex) + "/solution/" + jobIds.get(jobId) + path.substring(toIndex);

                                message.setPath(newPath);
                            }
                        } else if (message.getType().equals("response")) {
                            System.err.println("response: " + message);

                        } else if (message.getType().equals("replay")) {
                            // should contain 2 messages: request and response
                            List<Message> messages = allMessages.get(message.id);
                            if (messages != null && messages.size() == 2) {
                                Message response = messages.get(1);
                                if (!message.status.equals(response.status))
                                    System.err.println("status do not match " + message + " vs " + messages.get(0));
                                else if (message.getJobId().length() > 0) {
                                    System.err.println("response job id: " + response.getJobId() + " vs. replay job id: " + message.getJobId());
                                    jobIds.put(response.getJobId(), message.getJobId());
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
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
//
//{"distances":[[9010]],"times":[[708]],"info":{"copyrights":["GraphHopper","OpenStreetMap contributors"]}}

    static String parseJobId(String body) {
        // TODO would be a bit easier with a json lib, but for now it is sufficient
        if (body.startsWith("{") && body.contains("\"job_id\"")) {
            int index = body.indexOf("\"", body.indexOf(":"));
            int toIndex = body.indexOf("\"", index + 1);
            if (index >= 0 && toIndex > 0) {
                String jobId = body.substring(index, toIndex);
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
        String status;
        String method = "";
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
                status = reqString[1];
                jobId = parseJobId(getBody());
            } else if ("2".equals(type)) {
                typeInfo = "response";
                status = reqString[1];
                jobId = parseJobId(getBody());
            } else if ("1".equals(type)) {
                typeInfo = "request";
                // GET /xy
                method = reqString[1].split(" ")[0];
                path = reqString[1].split(" ")[1];
            } else
                throw new IllegalStateException("unknown message type " + type);
        }

        public String getType() {
            return type;
        }

        public String getMethod() {
            return method;
        }

        public void setJobId(String jobId) {
            this.jobId = jobId;
        }

        public void setPath(String path) {
            if (type.equals("1")) {
                if (!path.endsWith("\r"))
                    path += "\r";

                this.path = path;
                reqString[1] = method + " " + path;
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
            return typeInfo + " " + id + " " + path;
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
