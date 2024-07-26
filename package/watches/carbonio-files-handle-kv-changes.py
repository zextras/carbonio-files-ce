#!/bin/bash

# SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
#
# SPDX-License-Identifier: AGPL-3.0-only

import json
import base64
import sys
import subprocess
import pika


input_str = sys.stdin.read()
input_json = json.loads(input_str)

key = input_json['Key']
value = base64.b64decode(input_json['Value']).decode()

print(f"Key {key} has been changed into {value}.")

username = subprocess.run(["consul", "kv", "get", "-token-file=/etc/carbonio/files/service-discover/token", "carbonio-message-broker/default/username"], capture_output=True, text=True)
password = subprocess.run(["consul", "kv", "get", "-token-file=/etc/carbonio/files/service-discover/token", "carbonio-message-broker/default/password"], capture_output=True, text=True)

username = username.stdout.strip()
password = password.stdout.strip()

credentials = pika.PlainCredentials(username, password)
parameters = pika.ConnectionParameters('127.78.0.2', 20006, '/', credentials)

connection = pika.BlockingConnection(parameters)
channel = connection.channel()
channel.confirm_delivery()

exchange_name = 'KV_CHANGED_EXCHANGE'
channel.exchange_declare(exchange=exchange_name, exchange_type='fanout', durable=True)

print(f"Declared '{exchange_name}'")

message = json.dumps({"key": key, "value": value})
channel.basic_publish(
    exchange=exchange_name,
    routing_key='',
    body=message,
    properties=pika.BasicProperties(
        delivery_mode=pika.spec.PERSISTENT_DELIVERY_MODE,
    )
)

print("Message published")

connection.close()