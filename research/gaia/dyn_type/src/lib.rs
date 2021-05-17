//
//! Copyright 2020 Alibaba Group Holding Limited.
//!
//! Licensed under the Apache License, Version 2.0 (the "License");
//! you may not use this file except in compliance with the License.
//! You may obtain a copy of the License at
//!
//! http://www.apache.org/licenses/LICENSE-2.0
//!
//! Unless required by applicable law or agreed to in writing, software
//! distributed under the License is distributed on an "AS IS" BASIS,
//! WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//! See the License for the specific language governing permissions and
//! limitations under the License.

#[macro_use]
extern crate lazy_static;
extern crate pegasus;

extern crate dyn_clonable;

pub mod error;
pub mod object;
pub mod serde_dyn;
#[macro_use]
pub mod macros;
pub mod serde;

use dyn_clonable::*;
pub use error::CastError;
pub use object::{BorrowObject, Object, OwnedOrRef, Primitives};
pub use serde_dyn::{de_dyn_obj, register_type};
use std::any::Any;
use std::fmt::Debug;
use std::io;

#[clonable]
pub trait DynType: Any + Send + Sync + Clone + Debug {
    fn to_bytes(&self) -> io::Result<Vec<u8>>;
}
