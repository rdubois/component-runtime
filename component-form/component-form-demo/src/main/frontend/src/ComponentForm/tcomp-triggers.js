/**
 *  Copyright (C) 2006-2018 Talend Inc. - www.talend.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import deepClone from 'lodash.clonedeep';

function extract(root, path) {
  let object = root;
  const levels = path.split('.');
  for (const next of levels) {
    object = object[next];
    if (object === undefined) { // undefined, not falsy!
      return undefined;
    }
  }
  return object;
}

function validation({ schema, body }) {
  return {
    errors: {
      [schema.key]: body.status === 'KO' ? body.comment : undefined,
    }
  };
}

function schema({ schema, body, properties, trigger }) {
  if (!body.entries || !trigger.options || trigger.options.length == 0) {
    return { properties };
  }
  let newProperties = deepClone(properties);
  for (const option of trigger.options) {
    const lastDot = option.path.lastIndexOf('.');
    const parentPath = lastDot > 0 ? option.path.substring(0, lastDot) : option.path;
    const directChildPath = lastDot > 0 ? option.path.substring(lastDot + 1) : option.path;
    let mutable = parentPath === option.path ? newProperties : extract(newProperties, parentPath);
    if (!mutable) {
      continue;
    }
    mutable[directChildPath] = option.type === 'array' ? body.entries.map(e => e.name) : body.entries.reduce({}, (a, e) => {
      a[e.name] = e.type;
      return a;
    });
  }
  return {
    properties: newProperties,
    errors: {
      [schema.key]: body.error
    }
  };
}

function dynamic_values({ schema, body, properties, trigger }) {
  // for now it is set on the server side so no-op is ok
  return { properties };
}

export const TCompService = {
  extract() {
    return extract.apply(this, arguments);
  }
};

export default {
  dynamic_values,
  schema,
  healthcheck: validation,
  validation
};
