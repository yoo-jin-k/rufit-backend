#!/usr/bin/env python3
"""Verify HTTP persistence across a JVM restart using the dedicated test database."""
import uuid
from http_test_server import HttpTestServer

with HttpTestServer() as server:
    key = str(uuid.uuid4())
    status, workout = server.request('POST', '/api/v1/workouts', key=key)
    assert status == 201 and workout['id'] == key
    _, workout = server.request('PUT', f'/api/v1/workouts/{key}/exercises', {
        'version': workout['version'], 'sets': [{'exerciseName': '스쿼트', 'weight': 60.5, 'reps': 10}]})
    _, workout = server.request('PUT', f'/api/v1/workouts/{key}/sets/0/completion', {
        'version': workout['version'], 'completed': True, 'restSeconds': 90})
    deadline = workout['restEndsAt']
    server.restart()
    status, recovered = server.request('GET', '/api/v1/workouts/active')
    assert status == 200 and recovered['id'] == key
    assert recovered['sets'][0]['weight'] == 60.5 and recovered['completedSetIndexes'] == [0]
    assert recovered['restEndsAt'] == deadline
    _, replay = server.request('POST', '/api/v1/workouts', key=key)
    assert replay['id'] == key and replay['version'] == recovered['version']
    _, finished = server.request('POST', f'/api/v1/workouts/{key}/finish', {'version': recovered['version']})
    assert finished['finishedAt'] and finished['restEndsAt'] is None
    assert server.request('GET', '/api/v1/workouts/active')[0] == 204
    _, history = server.request('GET', '/api/v1/workouts')
    assert history['totalElements'] == 1 and history['items'][0]['id'] == key
    print('PASS: real HTTP write, JVM restart, active recovery, idempotent retry, finish, history')
