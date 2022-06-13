Tests were conducted on a machine with</br>
CPU: 11th Gen Intel(R) Core(TM) i9-11950H @ 2.60GHz</br>
RAM: 64GB</br></br>
Both versions were tested with 128 worker threads and a default number of event loop threads.</br>
During vertx 3 jmeter tests I have only modified the number of threads (users) with values: 500, 1000, 1500, 2000.</br>
While testing vertx 4, besides modifying number of threads (users) in jmeter, I have also modified the number of verticle instances (static ```NUMBER_OF_VERTICLE_INSTANCES```) to find the best setting.</br></br>
Jmeter version 5.4.3 was used for this test.</br>
To run the test paste the user.properties, vertx.bat and vertx.jmx to jmeter/bin 
and run jmeter.bat or create proper sh.</br></br>
To generate visual reports from provided results, use command
```
jmeter -g path/to/result.csv -o output/path
```