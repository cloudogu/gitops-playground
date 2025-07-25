package com.cloudogu.gitops.scm

import lombok.AllArgsConstructor
import lombok.Getter
import lombok.NoArgsConstructor
import lombok.Setter

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
class SCMCredentials {

   String password
   String url
   String username

}