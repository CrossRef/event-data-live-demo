# Event Data Live Demo

Very simple live-stream of Event Data in the browser. Accepts Events from the Event Bus and broadcasts them to people at home with web browsers.

This is not intended to be an API for live data access, or an information-critical system. It is just an illustration of the events that come through the system.

An Artifact containing a list of source-ids as a plaintext file is required, configured with `SOURCES_ARTIFACT`. For Crossref, this is `crossref-sourcelist`. This is read when the service starts. To reload, restart.

If the special value of "*" is passed as sources artifact, everything will accepted.

## To run

You can run a local copy with Docker Compose:

    docker-compose run -w /code -p "8101:8101" demo lein run

and send a demo event:

    curl --verbose --header "Content-Type: application/json" --data "{\"total\":1,\"id\":\"d24e5449-7835-44f4-b7e6-289da4900cd0\",\"message_action\":\"create\",\"subj_id\":\"https:\\/\\/es.wikipedia.org\\/wiki\\/Se%C3%B1alizaci%C3%B3n_paracrina\",\"subj\":{\"pid\":\"https:\\/\\/es.wikipedia.org\\/wiki\\/Se%C3%B1alizaci%C3%B3n_paracrina\",\"title\":\"Se\\u00f1alizaci\\u00f3n paracrina\",\"issued\":\"2016-09-25T23:58:58.000Z\",\"URL\":\"https:\\/\\/es.wikipedia.org\\/wiki\\/Se%C3%B1alizaci%C3%B3n_paracrina\",\"type\":\"entry-encyclopedia\"},\"source_id\":\"wikipedia\",\"relation_type_id\":\"references\",\"obj_id\":\"https:\\/\\/doi.org\\/10.1093\\/EMBOJ\\/20.15.4132\",\"occurred_at\":\"2016-09-25T23:58:58Z\"}"  -H "Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyIxIjoiMSIsInN1YiI6Indpa2lwZWRpYSJ9.w7zV2vtKNzrNDfgr9dfRpv6XYnspILRli_V5vd1J29Q" http://localhost:8101/events



## Configuration

Note that this service can be run in Docker Swarm with replication. The Swarm routing mesh might load-balance in-coming events, but they are all sent to the Redis instance for pubsub before being rebroadcast on websockets.

| Environment variable | Description                                            |
|----------------------|--------------------------------------------------------|
| `REDIS_HOST`         | Redis host                                             |
| `REDIS_PORT`         | Redis port                                             |
| `REDIS_DB`           | Redis DB number                                        |
| `STATUS_SERVICE`     | Public URL of the Status service                       |
| `JWT_SECRETS`        | Comma-separated list of JTW Secrets                    |
| `ARTIFACT_BASE`      | URL base of Artifact Repository                        |
| `SOURCES_ARTIFACT`   | Name of the artifact that provides the list of sources |

## License

Copyright © Crossref

Distributed under the The MIT License (MIT).

