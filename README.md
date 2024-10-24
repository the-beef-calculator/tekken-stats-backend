
# Tekken-Stats Backend

This is the backend service that will supplement the frontend for the website. The purpose of this monolith is to aggregate replay data, create player profiles based on said data, and then perform statistics analysis using the freshly made player profiles as data points.

## Technologies used
* **
* **Java (21)**
* **Spring Boot (3.3.4)**
* **PostgreSQL (17)**
* **RabbitMQ**
* **Docker**



## Lessons Learned (so far)
* **
* **Concurrency & Multithreading:** Lots and lots of race conditions and deadlocks. Figuring out how to mitigate them, and how to handle them gracefully without incurring data or performance losses.


* **Caching:** In order to improve the performance of the database, I decided to cache frequently accessed data rather than bombarding the database with existence checks.


* **Upsert vs. Query->Modify->Insert:** One of the main ways I mitigated race conditions was to modify rows atomically, rather than locking an entire table. This was done by upserting the *increments* of wins and losses, instead of the total wins and losses. This eliminated a good chunk of the race conditions and also eliminated the possibility of inserting stale data.


* **JPA vs JDBC:** One of the earliest bottlenecks I faced so far with JPA, particularly when I would query the database. The default `findAll()` and `saveAll()` methods are not batched by default (though you can change the `spring.jpa.properties.hibernate.jdbc.batch_size` to fix this), though in order to resolve race conditions and later implement upserting, I would need the more granular control that JDBC provided.


* **Database interfacing:** I had never worked with Postgres yet, nor have I built any personal projects (Java or otherwise) that used any sort of database. Understanding how java classes interopped with the database was fun, and I must say I am eternally grateful for the mighty Jdbc Template.




## Current Progress
* **
* **10/19/2024:** REST API endpoints have been created and are functioning perfectly! Average response times seem to be anywhere between 50-100ms which is amazing. Can't wait to see what it's like when everything is deployed.


* **10/15/2024:** Character Stats are now separated by game version. This hopefully will provide a more accurate look at characters after they have been adjus


* **10/12/2024:** Removed Bloom filter along with associated dependency. Returned to previous method of queueing for existence checks, but with a much more limited scope. Heavily refactored APIService class to support full dynamic loading of databases. APIService can now continue fetching historical data without the need for manual intervention. It will also switch to begin fetching forward once database preload is complete. Refactored JSON Parser, but still need to implement custom parser to not rely on Type Inference.


* **10/11/2024:** Removed the hardcoded timestamp value and let the server retrieve them dynamically. Refactored all model classes to fit new schema naming scheme.


* **10/10/2024:** Cleaned up pom.xml, added virtual thread functionality


* **10/08/2024:** Renamed classes to better suit their functionality. Squashed a bug that was causing wins and losses to not be recorded accurately in the database. Deleted a bunch of error log files that were generated when I was testing for deadlocks. Added a class to map rows to battle class.


* **10/05/2024:** Implemented proper use of upserts, which allowed me to remove the need for most existence checks/reads altogether. Changed Postgres schema to remove duplicate columns.


* **10/04/2024:** Reintroduced batched reads for Postgres. Implemented Bloom Filter cache in an effort to reduce the amount of reads made to the database.


* **10/03/2024:** Implemented pagination for batched upserts.


* **09/30/2024:** Migration from MongoDB to PostgreSQL. Squashed bug with that caused TekkenPower to not update correctly. Player class no longer has the character_stats class nested within. Player, Character_Stats, and Past_player_names are now their own classes and entities.


* **09/25/2024:** Added backpressure functionality to RabbitMQ


* **09/20/2024:** Removed PlayerDocument class, refactored Player class to include a nested character_stats class. Enabled retries incase a transaction were to fail.


* **09/19/2024:** Cleaned up logger messages, they now display correct INFO/WARN/ERRORs.


* **09/17/2024:** Refactored `ProcessReplays()` by splitting off into several helper methods.


* **09/15/2024:** Enabled multithreading and RabbitMQ as a message queue service. Added benchmark logs. Switched from `saveAll()` to `bulkOps` for true batched inserts and reads.


* **09/05/2024:** Added benchmark logs. Implemented batched inserts and reads.


* **09/04/2024:** Squashed bug with saving past player names correctly, Squashed a separate bug with last 10 battles not showing up, and ratings.


* **09/03/2024:** FIRST RUN! Completed replayservice class.


* **08/29/2024:** First query to mongoDB successful! Added player class and set up database connections. 



