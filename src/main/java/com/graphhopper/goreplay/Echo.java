package com.graphhopper.goreplay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;


public class Echo {
    public static String decodeHexString(String s) throws DecoderException {
        return new String(Hex.decodeHex(s.toCharArray()));
    }

    public static String encodeHexString(String s) {
        return new String(Hex.encodeHex(s.getBytes()));
    }

// the req parameter contains the following message (a replay in this case)
//3 047ec018ab89a2ddbc2e4ec21696f49943e93297 1529276174044003791 41483702
//HTTP/1.1 200 OK
//Date: Sun, 17 Jun 2018 22:56:14 GMT
//X-GH-Took: 14.0
//X-GH-Waiting: 0
//Content-Type: application/json
//Vary: Accept-Encoding
//Content-Length: 105
//
//{"distances":[[9010]],"times":[[708]],"info":{"copyrights":["GraphHopper","OpenStreetMap contributors"]}}

    public static String transformHTTPMessage(String req) {
        // System.err.println("REQUEST\n" + req +"\nEND\n\n");
        String[] reqString = req.split("\n");
        if(reqString.length == 0)
           return req;
           
        String type = reqString[0].split(" ")[0];
        String ID = reqString[0].split(" ")[1];
        // [2] => time (https://github.com/buger/goreplay/wiki/Middleware)
        // [3] => latency
        if("3".equals(type)) {
           System.err.println("REPLAY   " + ID + " " + reqString[1]);
           // TODO compare status
        } else if("2".equals(type)) {
           System.err.println("RESPONSE " + ID + " " + reqString[1]);
           // TODO grab job_id from response JSON
           for(int i = 0; i < reqString.length; i++) {
            if(reqString[i].length() < 2 && i+1 < reqString.length) {
              // TODO System.err.println(reqString[i+1]);
              break;
            }
          }
        } else if("1".equals(type)) {
           System.err.println("REQUEST  " + ID + " " + reqString[1]);
           // TODO if URL contains solution, then put into a queue e.g. on separate thread (+ println) if jobId is not yet in the map
           // TODO if jobId was found => rewrite URL
        } else {
           throw new IllegalStateException("UNKNOWN  " + ID + " " + reqString[1]);
        }
        return req;
    }

    public static void main(String[] args) throws DecoderException {
        if(args != null){
            for(String arg : args){
                System.out.println(arg);
            }

        }

        BufferedReader stdin = new BufferedReader(new InputStreamReader(
                                                                        System.in));
        String line = null;
        System.err.println("start");

        try {
            while ((line = stdin.readLine()) != null) {
                // TODO instead of reading directly, push into queue that can be used from a second thread that checks the jobIds
                String decodedLine = decodeHexString(line);

                String transformedLine = transformHTTPMessage(decodedLine);

                String encodedLine = encodeHexString(transformedLine);
                System.out.println(encodedLine);

            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
