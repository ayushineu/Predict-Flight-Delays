package com.neu.pdpmr.mappers;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Partitioner;


import com.neu.pdpmr.utils.CSVParser;
import com.neu.pdpmr.utils.FlightDataWritable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

/*
 * @author : Ayushi
 */

//mapper class to calculate the average delay;
//the mapper reads the file generated by the First MR job
public class CalculateMonthlyDelay {

    public static class AverageMonthlyDelayMapper extends Mapper<Object, Text, Text, FlightDataWritable> {

        private CSVParser csvParser = new CSVParser(',', '"');
        private static HashSet<String> topAirports = new HashSet<>();
        private static HashSet<String> topAirlines = new HashSet<>();
        private static HashMap<String,FlightDataWritable> airlineData = new HashMap<>();
        private static HashMap<String,FlightDataWritable> airportData = new HashMap<>();
        
        @Override
        protected void setup(Context context) throws IOException, InterruptedException {
            readFile("/part-r-00000",topAirlines,context);
            readFile("/part-r-00001",topAirports,context);
        }

        
        public static void readFile(String fileName, HashSet<String> target, Context context) throws IOException{
            Map<String,Integer> top = new HashMap<>();
            String outputPath = context.getConfiguration().get("output1"); // output of first file stored in output1
            Path p = new Path(outputPath+fileName);
            FileSystem fs = FileSystem.get(context.getConfiguration());
            BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(p)));
            try {
                String line;
                line = br.readLine();
                while (line != null) {
                    String keyValue[] = line.split("\\t"); 
                    top.put(keyValue[0].substring(3),Integer.parseInt(keyValue[1])); 
                    line = br.readLine();
                }
            } finally {
                br.close();
            }
            top = sortByValue(top);
            Iterator itr = top.entrySet().iterator();
            int count=0;
            while(itr.hasNext() && count<5){
                Map.Entry pair = (Map.Entry)itr.next();
                target.add(pair.getKey().toString());
                count++;
            }
        }

        /**
         * sort a hashmap by its value in descending order
         * @author Stack Overflow
         */        
        public static <K, V extends Comparable<? super V>> Map<K, V> 
        sortByValue(Map<K, V> map) {
        List<Map.Entry<K, V>> list = new LinkedList<Map.Entry<K, V>>(map.entrySet());
        Collections.sort( list, new Comparator<Map.Entry<K, V>>() {
            public int compare(Map.Entry<K, V> o1, Map.Entry<K, V> o2) {
                return (-1*(o1.getValue()).compareTo( o2.getValue() ));
            }
        });

        Map<K, V> result = new LinkedHashMap<K, V>();
        for (Map.Entry<K, V> entry : list) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

       
        @Override
        public void map(Object key, Text value, Context context)
                throws IOException, InterruptedException {

            String[] inVal = csvParser.parseLine(value.toString());
            //checks for null values
            if(isRecordValidAndRequired(inVal)){
                try{
                    if(Integer.parseInt(inVal[47]) == 0){       
                        if(isNotCancelled(inVal)){ //Flight not cancelled
                            double delay = 0;

                            if(Double.parseDouble(inVal[42]) > 0)
                                delay = Double.parseDouble(inVal[42]);

                            double normalizedDelay = delay / Integer.parseInt(inVal[50]);

                            if(topAirlines.contains(inVal[8]))
                                createNewHash(airlineData,inVal,"0",normalizedDelay);

                            if(topAirports.contains(inVal[23]))
                                createNewHash(airportData,inVal,"1",normalizedDelay);
                        }
                        else
                            return;
                    }
                    else if(Integer.parseInt(inVal[47]) == 1){  //Flight is cancelled
                        if(topAirlines.contains(inVal[8]))
                            createNewHash(airlineData,inVal,"0",4.0);

                        if(topAirports.contains(inVal[23]))
                            createNewHash(airportData,inVal,"1",4.0);
                    }
                    else
                        return; 
                }
                catch(Exception e){
                    return;
                }
            }
            else{
                return;
            }
        }

       
        public static void createNewHash(HashMap<String,FlightDataWritable> target,  String[] record,String type, double normalizedDelay){
                FlightDataWritable t;
                String hashmapKey = "";
                if(type.equals("0")){
                    hashmapKey = type + "_" + record[8] + "_" + record[23] + "_" + record[2] + "_" + record[0];
                    target = airlineData;
                }
                else {
                    hashmapKey = type + "_" + record[23] + "_" + record[8] + "_" + record[2] + "_" + record[0];
                    target = airportData;
                }
                if(target.containsKey(hashmapKey)){
                    t = target.get(hashmapKey);
                    double newVal = t.getNormalizedDelay().get() + normalizedDelay;
                    t.setNormalizedDelay(new DoubleWritable(newVal));
                    int noOfFlights = t.getFlights().get() + 1;
                    t.setFlights(new IntWritable(noOfFlights));
                }
                else{
                    t = new FlightDataWritable();
                    t.setFlights(new IntWritable(1));
                    t.setNormalizedDelay(new DoubleWritable(normalizedDelay));
                }
                target.put(hashmapKey,t);
            }

        
        public static boolean isNotCancelled(String[] record){
            try{
                
                if(Integer.parseInt(record[41])>0 && Integer.parseInt(record[30])>0 &&
                        Integer.parseInt(record[51])>0 && Double.parseDouble(record[42])>Integer.MIN_VALUE){
                    int timeZone = Integer.parseInt(record[40]) - Integer.parseInt(record[29]) - Integer.parseInt(record[50]);
                    int res = Integer.parseInt(record[41]) - Integer.parseInt(record[30]) - Integer.parseInt(record[51]) - timeZone;
                    if(res == 0)
                        return true;
                    else
                        return false;
                }
            }
            catch(Exception e){
                return false;
            }
            return false;
        }

        
        public static boolean isRecordValidAndRequired(String[] record){

            if (record == null || record.length == 0) 
                return false;

            if(checkIfNonZero(record) && checkIfNotEmpty(record) && timezoneCheck(record)) 
                return true;
            return false;
        }

       
        public static boolean timezoneCheck(String[] record){
            try {
                int timeZone = Integer.parseInt(record[40]) - Integer.parseInt(record[29]) - Integer.parseInt(record[50]);
                if(timeZone % 60 == 0)
                    return true;
            }
            catch(Exception e){
                return false;
            }
            return false;
        }

        
        public static boolean checkIfNotEmpty(String[] record){
            if(record[14].isEmpty() || record[23].isEmpty() || record[15].isEmpty() || record[24].isEmpty() ||
                    record[16].isEmpty() || record[25].isEmpty() || record[18].isEmpty() || record[27].isEmpty() ||
                    record[2].isEmpty())
                return false;
            return true;
        }

        
        public static boolean checkIfNonZero(String[] record){
            try{
                
                if(Integer.parseInt(record[11])>0 && Integer.parseInt(record[20])>0 &&
                        Integer.parseInt(record[12])>0 && Integer.parseInt(record[21])>0 &&
                        Integer.parseInt(record[13])>0 && Integer.parseInt(record[22])>0 &&
                        Integer.parseInt(record[17])>0 && Integer.parseInt(record[26])>0 &&
                        Integer.parseInt(record[19])>0 && Integer.parseInt(record[28])>0 &&
                        Integer.parseInt(record[40])!=0 && Integer.parseInt(record[29])!=0 &&
                        Integer.parseInt(record[2])>0 && Integer.parseInt(record[2])<13 &&   
                        Integer.parseInt(record[0])>=1989 && Integer.parseInt(record[0])<=2017){  
                    return true;
                }
            }
            catch(Exception e){
                return false;
            }
            return false;
        }

       
        @Override
        protected void cleanup(Context context) throws IOException, InterruptedException {
            Iterator airline_itr = airlineData.entrySet().iterator();
            Iterator airport_itr = airportData.entrySet().iterator();
            while(airline_itr.hasNext()){
                Map.Entry pair = (Map.Entry)airline_itr.next();
                context.write(new Text(String.valueOf(pair.getKey())),(FlightDataWritable)pair.getValue());
            }
            while(airport_itr.hasNext()){
                Map.Entry pair = (Map.Entry)airport_itr.next();
                context.write(new Text(String.valueOf(pair.getKey())),(FlightDataWritable) pair.getValue());
            }
        }
    }


   

   
    public static class AverageMonthlyPartitioner extends Partitioner<Text,FlightDataWritable> {

        @Override
        public int getPartition(Text text, FlightDataWritable flightDataWritable, int i) {
            String keys[] = text.toString().split("_");
            String airlineOrAirport = keys[0];
            String month = keys[3];
            if(airlineOrAirport.equals("0")) // "0" denotes Airline "1" denotes airport
                return Integer.parseInt(month) - 1;
            else
                return Integer.parseInt(month) + 11;
        }
    }
}