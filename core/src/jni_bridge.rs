//! JNI glue: lets Kotlin start/stop the server and receives each allowed request as a callback.
//! Only reachable on-device; the logic worth testing lives in gate.rs / server.rs.
use crate::server::{Handler, Response, Server};
use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JString, JValue};
use jni::sys::jlong;
use jni::{JNIEnv, JavaVM};
use std::sync::Arc;

const HANDLE_SIG: &str =
    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[B)Lcom/portal/calendar/NativeResponse;";

struct KotlinHandler {
    vm: JavaVM,
    target: GlobalRef,
}

impl KotlinHandler {
    fn call(&self, method: &str, path: &str, query: &str, body: Vec<u8>) -> jni::errors::Result<Response> {
        // Guard detaches the worker thread on drop; a thread exiting while attached crashes ART.
        let mut env = self.vm.attach_current_thread()?;
        let r = Self::invoke(&mut env, &self.target, method, path, query, body);
        if r.is_err() {
            // A pending Java exception must be cleared or the next JNI call aborts the VM.
            let _ = env.exception_clear();
        }
        r
    }

    fn invoke(env: &mut JNIEnv, target: &GlobalRef, method: &str, path: &str, query: &str, body: Vec<u8>) -> jni::errors::Result<Response> {
        let (m, p, q) = (env.new_string(method)?, env.new_string(path)?, env.new_string(query)?);
        let b = env.byte_array_from_slice(&body)?;
        let out = env
            .call_method(
                target,
                "handle",
                HANDLE_SIG,
                &[JValue::Object(&m), JValue::Object(&p), JValue::Object(&q), JValue::Object(&b)],
            )?
            .l()?;
        let status = env.get_field(&out, "status", "I")?.i()? as u16;
        let ct: JString = env.get_field(&out, "contentType", "Ljava/lang/String;")?.l()?.into();
        let content_type: String = env.get_string(&ct)?.into();
        let arr: JByteArray = env.get_field(&out, "body", "[B")?.l()?.into();
        Ok(Response { status, content_type, body: env.convert_byte_array(arr)? })
    }
}

impl Handler for KotlinHandler {
    fn handle(&self, method: &str, path: &str, query: &str, body: Vec<u8>) -> Response {
        self.call(method, path, query, body).unwrap_or_else(|_| Response {
            status: 500,
            content_type: "application/json".into(),
            body: br#"{"error":"internal error"}"#.to_vec(),
        })
    }
}

/// Returns an opaque handle for `stop`, or 0 after throwing IllegalStateException.
#[no_mangle]
pub extern "system" fn Java_com_portal_calendar_PortalCore_start(
    mut env: JNIEnv,
    _class: JClass,
    addr: JString,
    token: JString,
    handler: JObject,
) -> jlong {
    let result = (|| -> Result<Server, String> {
        let addr: String = env.get_string(&addr).map_err(|e| e.to_string())?.into();
        let token: String = env.get_string(&token).map_err(|e| e.to_string())?.into();
        let vm = env.get_java_vm().map_err(|e| e.to_string())?;
        let target = env.new_global_ref(&handler).map_err(|e| e.to_string())?;
        Server::start(&addr, &token, Arc::new(KotlinHandler { vm, target }))
    })();
    match result {
        Ok(s) => Box::into_raw(Box::new(s)) as jlong,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalStateException", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_portal_calendar_PortalCore_stop(_env: JNIEnv, _class: JClass, handle: jlong) {
    if handle != 0 {
        // SAFETY: handle came from `start` and Kotlin passes it here exactly once.
        unsafe { Box::from_raw(handle as *mut Server) }.stop();
    }
}
