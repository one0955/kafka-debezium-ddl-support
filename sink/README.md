# internshipA-sink

Copyright Debezium Authors.
Licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).

# Debezium JDBC Sink Connector - DDL, DML

MySQL to SingleStore 간의 DDL,DML CDC를 위해 Debezium JDBC Sink Connector를 기반으로 개발한 Sink Connector입니다. 

## Architecture

위키 참고

## Debezium JDBC Sink Connector - DDL

DDL topic을 전용으로 읽는 Sink Connector입니다. 이외 토픽 메시지를 읽으면 에러가 발생합니다. 기존의 Connector와 다른 특징은 다음과 같습니다.

* DDL 메시지 읽고 쿼리 전송(Only DDL 메시지만)
* DML Connector 상태 모니터링
* 메시지의 Ddl Version에 따라 DDL 쿼리 처리 판단(필요 시 대기)
* TargetDB(SingleStore)테이블로 DDL 버전 및 DML Task 상태 관리

### DDL Connector 특징1) DDL 메시지 읽고 쿼리 전송
기존의 Connector는 DDL 메시지내의 SchemaChangeValue 값을 인식하여 에러를 발생시킵니다. 해당 Connector는 반대 로직으로 DDL 메시지만 인식하여 처리하도록 구현되어 있습니다. 
해당 메시지가 전송가능 상태라면 메시지 안에 있는 쿼리 구문을 TargetDB로 전송합니다. 전송가능한 쿼리 구문 목록은 다음과 같습니다.

* CREATE TABLE
* DROP TABLE
* ALTER TABLE
* CREATE INDEX

### DDL Connector 특징2) DML Connector 상태 모니터링
DDL 메시지만 처리하기 때문에 DML 메시지와의 순서를 보장하기 위해선 DML Connector의 상태를 모니터링 할 필요가 있습니다. config 설정에 DML Connector에 대한 Connect API URL을 입력하여 해당 Connector의 task가 모두 정상 동작할 때만 작동하게 됩니다.
config 설정에 아래와 같은 구문을 추가합니다.

* "ddl.dmltasks.uri": "http://localhost:8083/connectors/jdbc-sink-connector-dml-v4/status"

### DDL Connector 특징3) 메시지의 Ddl Version에 따른 DDL 쿼리 처리 로직
메시지 내의 Ddl Version을 보고 해당 DDL 쿼리를 생략, 처리, 대기할지 판단합니다. Ddl Version은 "`DBname`.`tablename`/`gtid`" 형식의 String 값입니다. 
해당 로직을 위해선 반영할 테이블명을 알아야 합니다. config 설정에 반영할 테이블 명을 아래와 같이 입력합니다. 

* "ddl.tablelist": "customers3"

처리 로직은 크게 아래의 단계를 따릅니다. 

1. 해당 메시지 Version에 있는 table명과 config의 table명을 비교하여 다르면 생략합니다.
2. 이전에 처리한 Version과 같으면 생략합니다.(중복 방지)
3. 모든 DML Task들의 이전 Version 메시지 처리 완료까지 대기하다가 완료되면 해당 DDL 메시지를 처리합니다.
4. TargetDB에 새로운 Version을 반영합니다.(3번의 DDL 쿼리와 원자적으로 수행됩니다)

### DDL Connector 특징4) TargetDB(SingleStore)로 DDL 버전 및 DML Task 상태 관리
DDL, DML Connector 간의, 그리고 task 간의 정보 공유를 위해서 TargetDB에서 각종 정보를 관리하는 로직을 추가하였습니다. 이때 TargetDB와의 모든 상호작용은 `TaskStateInDBManager` 클래스를 통해서 이루어집니다.
DDL, DML Connector 모두 `TaskStateInDBManager` 클래스를 통해 '버전 및 상태 관리'를 수행하여 사용 및 관리의 편의성을 증가시켰습니다.
또한 DDL 쿼리와 Version Update 쿼리를 단일 Transaction으로 처리하여 원자성을 보장하였습니다. 
   

## Debezium JDBC Sink Connector - DML

DML topic을 전용으로 읽는 Sink Connector입니다. 이외 토픽 메시지를 읽으면 에러가 발생합니다. 기존의 Connector와 다른 특징은 다음과 같습니다. 

* 메시지의 Ddl Version에 따라 DML 쿼리 처리 판단(필요 시 대기)
* TargetDB(SingleStore)테이블로 DDL 버전 및 DML Task 상태 관리

### DML Connector 특징1) 메시지의 Ddl Version에 따른 DML 쿼리 처리 로직
메시지 내의 Ddl Version을 보고 해당 DML 쿼리를 처리, 대기할지 판단합니다. Ddl Version은 "`DBname`.`tablename`/`gtid`" 형식의 String 값입니다. 
DDL Connector와 마찬가지로 해당 로직을 위해선 반영할 테이블명을 알아야 합니다. config 설정에 반영할 테이블 명을 아래와 같이 입력합니다. `ddl. -> dml.`로 변경되었습니다.  

* "dml.tablelist": "customers3"

처리 로직은 크게 아래의 단계를 따릅니다. 

1. TargetDB에서 해당 테이블에 맞는 Ddl Version을 읽어옵니다. 
2-1. DML 메시지 Version과 현재 Version이 같으면 그대로 처리합니다.
2-2. DML 메시지 Version과 현재 Version이 다르면 TargetDB에 Version이 업데이트 될때까지 대기합니다.(또한 TargetDB에 해당 Task를 대기 상태로 업데이트) 

### DDL Connector 특징2) TargetDB(SingleStore)로 DDL 버전 및 DML Task 상태 관리
DDL Connector와 마찬가지로 `TaskStateInDBManager` 클래스를 활용합니다. DDL Connector과 비교해보면 사용하는 기능에 조금 차이가 있습니다. 
* DDL Connector: Ddl Version 업데이트, DML Tasks 상태 모니터링
* DML Connector: Ddl Version 읽기, 자신의 DML Task 상태 업데이트

# 실행 방법
각 프로젝트 폴더에서 설명

## License

This project is licensed under the Apache License, version 2.
