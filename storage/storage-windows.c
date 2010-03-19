#include <stdio.h>
#include <stdlib.h>
#define __USE_GNU /* strnlen */
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <errno.h>
#include <dirent.h> /* open|read|close dir */
#include <time.h> /* time() */
#include <stdint.h>

#include <openssl/sha.h>
#include <openssl/rsa.h>
#include <openssl/pem.h>
#include <openssl/bio.h>
#include <openssl/evp.h>
#include <openssl/err.h>

#include <storage-windows.h>
#include <euca_auth.h>

int decryptWindowsPassword(char *encpass, int encsize, char *pkfile, char **out) {
  FILE *PKFP;
  RSA *pr=NULL;
  char *dec64;
  int rc;
  
  if (!encpass || encsize <= 0 || !*pkfile || !out) {
    return(1);
  }

  PKFP = fopen(pkfile, "r");
  if (!PKFP) {
    return(1);
  }
  if (PEM_read_RSAPrivateKey(PKFP, &pr, NULL, NULL) == NULL) {
    return(1);
  }
  
  dec64 = base64_dec((unsigned char *)encpass, strlen(encpass));
  if (!dec64) {
    return(1);
  }

  *out = malloc(512);
  bzero(*out, 512);  
  rc = RSA_private_decrypt(encsize, (unsigned char *)dec64, (unsigned char *)*out, pr, RSA_PKCS1_PADDING);    
  if (!*out) {
    return(1);
  }

  return(0);
}


int encryptWindowsPassword(char *pass, char *key, char **out, int *outsize) {
  char *sshkey_dec, *modbuf, *exponentbuf;
  char *ptr, *tmp, hexstr[2], *enc64;
  char *dec64, encpassword[512];

  uint32_t len, exponent;
  int size, ilen, i, encsize=0, rc;
  RSA *r=NULL;
  
  if (!pass || !key || !out || !outsize) {
    return(1);
  }
  
  size = strlen(key);
  sshkey_dec = base64_dec((unsigned char *)key, size);
  if (!sshkey_dec) {
    return(1);
  }
  
  ptr = sshkey_dec;
  memcpy(&len, ptr, 4);
  len = htonl(len);
  ptr+=4+len;

  memcpy(&len, ptr, 4);
  len = htonl(len);
  ptr+=4;
  
  // read public exponent
  exponentbuf = malloc(32768);
  exponent = 0;
  memcpy(&exponent, ptr, len);
  exponent = htonl(exponent);
  exponent = htonl(exponent);
  snprintf(exponentbuf, 128, "%08X", exponent);
  ptr+=len;
  
  memcpy(&len, ptr, 4);
  len = htonl(len);
  ptr+=4;
  
  // read modulus material
  modbuf = malloc(32768);
  bzero(modbuf, 32768);
  ilen = (int)len;
  for (i=0; i<ilen; i++) {
    tmp = strndup(ptr, 1);
    len = *tmp;
    sprintf(hexstr, "%02X", (len<<24)>>24);
    strcat(modbuf, hexstr);
    ptr++;
  }
  //  printf("MOD: |%s|\n", modbuf);
  //  printf("EXPONENT: |%s|\n", exponentbuf);
    
  r = RSA_new();
  if (!r) {
    free(modbuf); free(exponentbuf);
    return(1);
  }
  if (!BN_hex2bn(&(r->e), exponentbuf) || !BN_hex2bn(&(r->n), modbuf)) {
    free(modbuf); free(exponentbuf);
    return(1);
  }

  bzero(encpassword, 512);
  encsize = RSA_public_encrypt(strlen(pass), (unsigned char *)pass, (unsigned char *)encpassword, r, RSA_PKCS1_PADDING);
  if (encsize <= 0) {
    free(modbuf); free(exponentbuf);
    return(1);
  }

  *out = base64_enc((unsigned char *)encpassword, encsize);
  *outsize = encsize;
  if (!*out || *outsize <= 0) {
    free(modbuf); free(exponentbuf);
    return(1);
  }
  
  return(0);
}


int makeWindowsFloppy(char *euca_home, char *rundir_path, char *keyName, char *password) {
  int fd, rc, rbytes, count;
  char *buf, *ptr, *tmp, *newpass, dest_path[1024], source_path[1024], fname[1024];

  snprintf(source_path, 1024, "%s/usr/share/eucalyptus/floppy", euca_home);
  snprintf(dest_path, 1024, "%s/floppy", rundir_path);

  buf = malloc(1024 * 2048);
  if (!buf) {
    return(1);
  }
  
  fd = open(source_path, O_RDONLY);
  if (fd < 0) {
    if (buf) free(buf);
    return(1);
  }
  
  rbytes = read(fd, buf, 1024 * 2048);
  close(fd);
  
  ptr = buf;
  count=0;
  tmp = malloc(sizeof(char) * strlen("MAGICEUCALYPTUSPASSWORDPLACEHOLDER")+1);
  newpass = malloc(sizeof(char) * strlen("MAGICEUCALYPTUSPASSWORDPLACEHOLDER")+1);
  
  bzero(tmp, strlen("MAGICEUCALYPTUSPASSWORDPLACEHOLDER")+1);
  bzero(newpass, strlen("MAGICEUCALYPTUSPASSWORDPLACEHOLDER")+1);
  snprintf(newpass, strlen(password)+1, "%s", password);
  
  while(count < rbytes) {
    memcpy(tmp, ptr, strlen("MAGICEUCALYPTUSPASSWORDPLACEHOLDER"));
    if (!strcmp(tmp, "MAGICEUCALYPTUSPASSWORDPLACEHOLDER")) {
      memcpy(ptr, newpass, strlen("MAGICEUCALYPTUSPASSWORDPLACEHOLDER"));
    }
    ptr++;
    count++;
  }

  fd = open(dest_path, O_CREAT | O_TRUNC | O_RDWR);
  if (fd < 0) {
    if (buf) free(buf);
    return(1);
  }
  rc = write(fd, buf, rbytes);
  if (rc != rbytes) {
    if (buf) free(buf);
    return(1);
  }
  close(fd);
  if (buf) free(buf);
  return(0);
}
