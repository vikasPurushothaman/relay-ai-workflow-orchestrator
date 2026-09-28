"""Real MySQL assertions for the approved domain migration, used by startup checker."""
import subprocess


def verify_schema(sql, compose, env):
    passed=0
    def reject(statement, code):
        nonlocal passed
        result=subprocess.run(compose+['exec','-T','mysql','sh','-c',
            'MYSQL_PWD="$(cat /run/secrets/mysql_password)" exec mysql --protocol=TCP -h 127.0.0.1 -u relay -D relay -N -B'],
            env=env,input=statement,text=True,capture_output=True,timeout=20)
        assert result.returncode!=0 and f'ERROR {code} ' in result.stderr,(statement,result.stderr)
        passed+=1
    tables=sql("SELECT table_name FROM information_schema.tables WHERE table_schema='relay' ORDER BY table_name;").splitlines()
    assert tables==['approvals','flyway_schema_history','queue_jobs','runs','step_attempts','steps','workflows']
    assert sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='relay' AND table_name<>'flyway_schema_history' AND engine='InnoDB' AND table_collation='utf8mb4_0900_bin';")=='6'
    expected_indexes={'ix_workflows_created':'created_at,id','ix_runs_created':'created_at,run_id','ix_runs_workflow':'workflow_id,created_at,run_id','ix_runs_status':'status,created_at,run_id','ix_steps_node':'run_id,node_id,sequence','ix_approvals_pending':'status,closed_at,created_at,id','ix_approvals_gate':'run_id,status,step_sequence','ix_queue_due':'status,available_at,run_id','ix_queue_expired':'status,lease_until,run_id','ix_queue_step':'run_id,step_sequence'}
    for name,columns in expected_indexes.items():
        assert sql(f"SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema='relay' AND index_name='{name}';")==columns
    assert sql("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='relay' AND delete_rule='RESTRICT' AND update_rule='RESTRICT';")=='6'
    t="'2026-09-25 00:00:00.123456'"
    workflow="INSERT INTO workflows(id,name,status,draft_definition,revision,created_at,updated_at) VALUES ('wf','Workflow','draft','{}',0,"+t+','+t+');'
    sql(workflow)
    sql(workflow.replace("'wf'","'WF'"))
    sql(workflow.replace("'wf'","REPEAT('x',128)"))
    reject(workflow,1062)
    reject(workflow.replace("'wf'","REPEAT('y',129)"),1406)
    reject("UPDATE workflows SET draft_definition='not json' WHERE id='wf';",3140)
    reject("UPDATE workflows SET draft_definition=NULL WHERE id='wf';",1048)
    for change in ["status='published'","published_at="+t,"published_definition='{}'","status='DRAFT'","revision=-1","updated_at='2025-01-01'"]:
        reject('UPDATE workflows SET '+change+" WHERE id='wf';",3819)
    sql("UPDATE workflows SET published_definition='{}',published_at="+t+",status='published' WHERE id='wf';")
    run="INSERT INTO runs(run_id,workflow_id,definition_snapshot,execution_policy,input,trigger_type,status,steps_executed,next_step_sequence,ai_usage_complete,created_at,revision) VALUES ('r','wf','{}','{}','{}','manual','queued',0,1,1,"+t+",0);"
    sql(run);sql(run.replace("'r'","'r2'"))
    reject(run,1062)
    reject(run.replace("'r'","'orphan'").replace("'wf'","'missing'"),1452)
    for change in ["status='unknown'","trigger_type='schedule'","steps_executed=-1","next_step_sequence=0","ai_tokens_used=-1","revision=-1","ai_usage_complete=2","status='succeeded'","finished_at="+t,"started_at='2025-01-01'","cancel_requested_at='2025-01-01'"]:
        reject('UPDATE runs SET '+change+" WHERE run_id='r';",3819)
    sql("UPDATE runs SET status='cancelled',finished_at="+t+" WHERE run_id='r2';")
    step="INSERT INTO steps(run_id,`sequence`,node_id,node_type,status,final_attempt,ai_repair_count,ai_usage_complete) VALUES ('r',1,'loop','notify','running',0,0,1);"
    sql(step);sql(step.replace("'r',1","'r',2"));sql(step.replace("'r',1","'r',3"))
    reject(step,1062)
    reject(step.replace("'r',1","'r',0"),3819)
    reject(step.replace("'r',1","'missing',1"),1452)
    sql("UPDATE steps SET idempotency_key='r:1',output='null' WHERE run_id='r' AND `sequence`=1;")
    assert sql("SELECT output IS NULL,JSON_TYPE(output) FROM steps WHERE run_id='r' AND `sequence`=1;")=='0\tNULL'
    assert sql("SELECT output IS NULL FROM steps WHERE run_id='r' AND `sequence`=2;")=='1'
    reject("UPDATE steps SET idempotency_key='r:1' WHERE run_id='r' AND `sequence`=2;",1062)
    for change in ["status='queued'","status='waiting'","wait_reason='delay'","status='waiting',wait_reason='other'","ai_repair_count=2","ai_repair_count=1","ai_repair_request='{}'","final_attempt=-1","tokens_prompt=-1","tokens_completion=-1","ai_usage_complete=2","duration_ms=-1","status='succeeded'","finished_at="+t,"started_at="+t+",finished_at='2025-01-01',status='failed',duration_ms=0"]:
        reject('UPDATE steps SET '+change+" WHERE run_id='r' AND `sequence`=1;",3819)
    sql("UPDATE steps SET status='waiting',wait_reason='approval',ai_repair_count=1,ai_repair_request='{}' WHERE run_id='r' AND `sequence`=1;")
    attempt="INSERT INTO step_attempts(run_id,step_sequence,attempt_no,status,cause,claim_generation,started_at) VALUES ('r',1,1,'running','initial',0,"+t+");"
    sql(attempt);reject(attempt,1062)
    reject(attempt.replace("'r',1,1","'r2',1,1"),1452)
    reject(attempt.replace("'r',1,1","'r',1,0"),3819)
    for change in ["status='waiting'","cause='bogus'","claim_generation=-1","tokens_prompt=-1","tokens_completion=-1","duration_ms=-1","status='succeeded'","finished_at="+t,"status='failed',duration_ms=0,finished_at='2025-01-01'"]:
        reject('UPDATE step_attempts SET '+change+" WHERE run_id='r';",3819)
    sql("UPDATE step_attempts SET status='uncertain' WHERE run_id='r';")
    sql("UPDATE step_attempts SET status='succeeded',duration_ms=0,finished_at="+t+" WHERE run_id='r';")
    approval="INSERT INTO approvals(id,run_id,step_sequence,node_id,message,status,created_at) VALUES ('a','r',1,'loop','Review','pending',"+t+");"
    sql(approval)
    reject(approval,1062);reject(approval.replace("'a'","'a2'"),1062)
    reject(approval.replace("'a','r'","'a2','r2'"),1452)
    for change in ["status='other'","status='approved'","decided_by='human'","decided_at="+t,"status='closed'","closed_at="+t,"status='approved',decided_by='human',decided_at='2025-01-01'"]:
        reject('UPDATE approvals SET '+change+" WHERE id='a';",3819)
    sql("UPDATE approvals SET status='approved',decided_by='human',decided_at="+t+" WHERE id='a';")
    reject("UPDATE approvals SET closed_at="+t+",close_reason='cancelled' WHERE id='a';",3819)
    sql(approval.replace("'a','r',1","'a2','r',2"))
    sql("UPDATE approvals SET status='closed',closed_at="+t+",close_reason='cancelled' WHERE id='a2';")
    job="INSERT INTO queue_jobs(run_id,target_node_id,status,available_at,claim_generation,retry_count,created_at,updated_at) VALUES ('r','loop','ready',"+t+",0,0,"+t+','+t+');'
    sql(job);reject(job,1062)
    reject(job.replace("'r'","'missing'"),1452)
    sql(job.replace("'r'","'r2'"))
    reject("UPDATE queue_jobs SET step_sequence=1 WHERE run_id='r2';",1452)
    for change in ["status='other'","status='leased'","lease_owner='worker'","lease_until="+t,"claim_generation=-1","retry_count=-1","step_sequence=0","next_attempt_cause='other'","updated_at='2025-01-01'"]:
        reject('UPDATE queue_jobs SET '+change+" WHERE run_id='r';",3819)
    sql("UPDATE queue_jobs SET status='leased',lease_owner='worker',lease_until="+t+",step_sequence=1,next_attempt_cause='recovery' WHERE run_id='r';")
    reject("UPDATE queue_jobs SET status='inactive' WHERE run_id='r';",3819)
    for statement in ["DELETE FROM workflows WHERE id='wf';","DELETE FROM runs WHERE run_id='r';","DELETE FROM steps WHERE run_id='r' AND `sequence`=1;"]:
        reject(statement,1451)
    sql("START TRANSACTION; UPDATE workflows SET name='rolled back' WHERE id='wf'; ROLLBACK;")
    assert sql("SELECT name FROM workflows WHERE id='wf';")=='Workflow'
    assert sql("SELECT DATE_FORMAT(created_at,'%f') FROM workflows WHERE id='wf';")=='123456'
    assert sql("SELECT COUNT(*) FROM steps WHERE node_id='loop' AND run_id='r';")=='3'
    print(f'PASS: six domain tables/indexes/FKs, valid relationships/loops/JSON-null/rollback and {passed} enforced negative SQL cases',flush=True)
