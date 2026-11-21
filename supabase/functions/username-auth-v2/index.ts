import { createClient } from "https://esm.sh/@supabase/supabase-js@2.119.0";
const url = Deno.env.get("SUPABASE_URL")!;
const publishable = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg";
const secretMap = Deno.env.get("SUPABASE_SECRET_KEYS");
const secret = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? (secretMap ? JSON.parse(secretMap).default : "");
if (!secret) throw new Error("Missing server secret");
const auth = createClient(url, publishable, {auth:{persistSession:false,autoRefreshToken:false}});
const admin = createClient(url, secret, {auth:{persistSession:false,autoRefreshToken:false}});
const allowedOrigins = new Set(["https://appassets.androidplatform.net"]);
const responseHeaders = (origin: string | null) => {
  const h: Record<string,string> = {
    "Access-Control-Allow-Headers":"authorization, apikey, content-type, x-client-info",
    "Access-Control-Allow-Methods":"POST, OPTIONS",
    "Access-Control-Max-Age":"600",
    "Content-Type":"application/json; charset=utf-8",
    "Vary":"Origin"
  };
  if (origin && allowedOrigins.has(origin)) h["Access-Control-Allow-Origin"] = origin;
  return h;
};
const out=(data:unknown,status=200,origin:string|null=null)=>new Response(JSON.stringify(data),{status,headers:responseHeaders(origin)});
const norm=(v:unknown)=>String(v??"").trim().normalize("NFKC").toLowerCase();
const validName=(v:string)=>v.length>=3&&v.length<=32&&/^[a-z0-9_.-]+$/.test(v);
const validEmail=(v:string)=>{const i=v.indexOf("@");return i>0&&!v.includes(" ")&&v.lastIndexOf(".")>i+1;};
Deno.serve(async(req:Request)=>{
 const origin = req.headers.get("origin");
 if (origin && !allowedOrigins.has(origin)) return out({error:"مبدأ درخواست مجاز نیست."},403,origin);
 if(req.method==="OPTIONS")return new Response(null,{status:204,headers:responseHeaders(origin)});
 if(req.method!=="POST")return out({error:"درخواست نامعتبر است."},405,origin);
 try{
  const raw=await req.text();if(raw.length>12000)return out({error:"درخواست بیش از حد بزرگ است."},413,origin);
  const b=JSON.parse(raw),action=String(b.action??""),username=norm(b.username),password=String(b.password??"");
  if(action==="register"){
   const suppliedEmail=String(b.email??"").trim().toLowerCase();
   const accountType=String(b.account_type??"");
   const firstName=String(b.first_name??"").trim();
   const lastName=String(b.last_name??"").trim();
   const mobile=String(b.mobile??"").trim();
   const companyName=String(b.company_name??"").trim();
   const activityCity=String(b.activity_city??"").trim();
   const officeAddress=String(b.office_address??"").trim();
   const nationalId=String(b.national_id??"").trim();
   const smartCard=String(b.smart_card_number??"").trim();
   const vehicleType=String(b.vehicle_type??"").trim();
   const plate=String(b.license_plate??"").trim();
   const primaryDestination=String(b.primary_destination??"").trim();
   const destinations=Array.isArray(b.destinations)?b.destinations.map((x:unknown)=>String(x).trim()).filter(Boolean):[];
   const digits=(v:string)=>v.replace(/[^۰-۹٠-٩0-9]/g,"").replace(/[۰-۹]/g,(c)=>String("۰۱۲۳۴۵۶۷۸۹".indexOf(c))).replace(/[٠-٩]/g,(c)=>String("٠١٢٣٤٥٦٧٨٩".indexOf(c)));
   const mobileDigits=digits(mobile);
   const normalizedMobile=mobileDigits.startsWith("0098")?"0"+mobileDigits.slice(4):mobileDigits.startsWith("98")?"0"+mobileDigits.slice(2):mobileDigits.startsWith("9")&&mobileDigits.length===10?"0"+mobileDigits:mobileDigits;
   const normalizedNationalId=digits(nationalId);
   if(!["company","driver"].includes(accountType))return out({error:"نوع حساب را مشخص کنید: شرکت باربری یا راننده."},400,origin);
   if(!firstName||firstName.length>80||!lastName||lastName.length>100)return out({error:"نام و نام خانوادگی را کامل و معتبر وارد کنید."},400,origin);
   if(!/^09[0-9]{9}$/.test(normalizedMobile))return out({error:"شماره موبایل باید با 09 و ۱۱ رقم وارد شود."},400,origin);
   if(!validName(username))return out({error:"نام کاربری باید ۳ تا ۳۲ نویسه و شامل حروف انگلیسی، عدد، نقطه، خط تیره یا زیرخط باشد."},400,origin);
   if(suppliedEmail && !validEmail(suppliedEmail))return out({error:"ایمیل واردشده معتبر نیست."},400,origin);
   if(password.length<8||password.length>72)return out({error:"رمز عبور باید حداقل ۸ نویسه باشد."},400,origin);
   if(accountType==="company"){
    if(companyName.length<2||companyName.length>160||activityCity.length<2||activityCity.length>100)return out({error:"نام شرکت و شهر محل فعالیت را کامل وارد کنید."},400,origin);
   }else{
    const vehicles=["وانت مبلی","کامیونت مبلی","کامیون ۶ متری","کامیون بزرگ","خاور مبلی پتو دار","کامیون دربستی کار","سایر خودروهای مبلی"];
    if(!/^[0-9]{10}$/.test(normalizedNationalId))return out({error:"کد ملی باید ۱۰ رقم باشد."},400,origin);
    if(smartCard.length<3||smartCard.length>40)return out({error:"شماره کارت هوشمند معتبر نیست."},400,origin);
    if(!vehicles.includes(vehicleType))return out({error:"نوع خودرو را از فهرست انتخاب کنید."},400,origin);
    if(plate.length<4||plate.length>30)return out({error:"شماره پلاک معتبر نیست."},400,origin);
    if(primaryDestination.length<2||primaryDestination.length>100)return out({error:"مقصد اصلی را وارد کنید."},400,origin);
   }
   const {data:exists}=await admin.from("account_usernames").select("user_id").eq("username_normalized",username).maybeSingle();
   if(exists)return out({error:"این نام کاربری قبلاً انتخاب شده است."},409,origin);
   const email=suppliedEmail || (username + "@accounts.elambar.invalid");
   let createdUser:any=null, session:any=null;
   if(suppliedEmail){
    const {data,error}=await auth.auth.signUp({email,password,options:{data:{account_type:accountType,first_name:firstName,last_name:lastName,full_name:firstName+" "+lastName,username}}});
    if(error||!data.user||(data.user.identities?.length===0))return out({error:"ساخت حساب انجام نشد. نام کاربری ممکن است تکراری باشد یا اطلاعات معتبر نباشد."},400,origin);
    createdUser=data.user;session=data.session;
   }else{
    const {data,error}=await admin.auth.admin.createUser({email,password,email_confirm:true,user_metadata:{account_type:accountType,first_name:firstName,last_name:lastName,full_name:firstName+" "+lastName,username},app_metadata:{account_type:accountType}});
    if(error||!data.user)return out({error:"ساخت حساب انجام نشد. نام کاربری را تغییر دهید و دوباره تلاش کنید."},400,origin);
    createdUser=data.user;
   }
   const rollback=async()=>{await admin.auth.admin.deleteUser(createdUser.id);};
   const {error:metaError}=await admin.auth.admin.updateUserById(createdUser.id,{app_metadata:{account_type:accountType}});
   if(metaError){await rollback();return out({error:"ثبت نوع حساب انجام نشد؛ دوباره تلاش کنید."},500,origin);}
   const {error:claimError}=await admin.from("account_usernames").insert({user_id:createdUser.id,username});
   if(claimError){await rollback();return out({error:"ذخیره نام کاربری انجام نشد؛ احتمالاً نام تکراری است."},409,origin);}
   const profile=accountType==="company"
    ? {id:createdUser.id,company_name:companyName,manager_name:firstName+" "+lastName,mobile:normalizedMobile,activity_city:activityCity,office_address:officeAddress||null,first_name:firstName,last_name:lastName,status:"pending"}
    : {id:createdUser.id,full_name:firstName+" "+lastName,mobile:normalizedMobile,national_id:normalizedNationalId,smart_card_number:smartCard,vehicle_type:vehicleType,license_plate:plate,primary_destination:primaryDestination,destinations:Array.from(new Set([primaryDestination,...destinations])).slice(0,30),first_name:firstName,last_name:lastName,status:"pending"};
   const table=accountType==="company"?"companies":"drivers";
   const {error:profileError}=await admin.from(table).insert(profile);
   if(profileError){await rollback();return out({error:"ساخت پرونده انجام نشد. اطلاعات واردشده را بررسی و دوباره تلاش کنید."},400,origin);}
   return out({user:createdUser,session,needsEmailConfirmation:!!suppliedEmail&&!session},200,origin);
  }
  if(action==="update_profile"){
   const bearer=req.headers.get("authorization")??"";
   const token=bearer.replace(/^Bearer\s+/i,"").trim();
   if(!token)return out({error:"نشست ورود معتبر نیست؛ دوباره وارد شوید."},401,origin);
   const {data:verified,error:verifyError}=await auth.auth.getUser(token);
   const currentUser=verified.user;
   if(verifyError||!currentUser?.id)return out({error:"نشست ورود منقضی شده است؛ دوباره وارد شوید."},401,origin);
   const accountType=String(currentUser.app_metadata?.account_type??"");
   if(!["driver","company"].includes(accountType))return out({error:"نوع حساب معتبر نیست."},403,origin);
   const firstName=String(b.first_name??"").trim();
   const lastName=String(b.last_name??"").trim();
   const mobileRaw=String(b.mobile??"").trim();
   const birthDateRaw=String(b.birth_date??"").trim();
   if(birthDateRaw && !/^\d{4}-\d{2}-\d{2}$/.test(birthDateRaw))return out({error:"تاریخ تولد معتبر نیست."},400,origin);
   if(birthDateRaw && Number.isNaN(Date.parse(birthDateRaw+"T00:00:00Z")))return out({error:"تاریخ تولد معتبر نیست."},400,origin);
   const digits=(v:string)=>v.replace(/[^۰-۹٠-٩0-9]/g,"").replace(/[۰-۹]/g,(c)=>String("۰۱۲۳۴۵۶۷۸۹".indexOf(c))).replace(/[٠-٩]/g,(c)=>String("٠١٢٣٤٥٦٧٨٩".indexOf(c)));
   const mobileDigits=digits(mobileRaw);
   const mobile=mobileDigits.startsWith("0098")?"0"+mobileDigits.slice(4):mobileDigits.startsWith("98")?"0"+mobileDigits.slice(2):mobileDigits.startsWith("9")&&mobileDigits.length===10?"0"+mobileDigits:mobileDigits;
   if(!firstName||firstName.length>80||!lastName||lastName.length>100)return out({error:"نام و نام خانوادگی را کامل وارد کنید."},400,origin);
   if(!/^09[0-9]{9}$/.test(mobile))return out({error:"شماره موبایل باید ۱۱ رقم و با 09 شروع شود."},400,origin);
   const password=b.new_password===undefined?"":String(b.new_password);
   if(password && (password.length<8||password.length>72))return out({error:"رمز جدید باید حداقل ۸ نویسه باشد."},400,origin);
   const table=accountType==="driver"?"drivers":"companies";
   const {data:currentProfile,error:currentProfileError}=await admin.from(table).select("mobile").eq("id",currentUser.id).maybeSingle();
   if(currentProfileError||!currentProfile)return out({error:"پرونده کاربری پیدا نشد."},404,origin);
   if(mobile!==String(currentProfile.mobile??""))return out({error:"برای تغییر شماره موبایل ابتدا باید تأیید پیامکی انجام شود."},403,origin);
   const profile:any={first_name:firstName,last_name:lastName};
   profile.birth_date=birthDateRaw||null;
   if(accountType==="driver"){
    const vehicles=["وانت مبلی","کامیونت مبلی","کامیون ۶ متری","کامیون بزرگ","خاور مبلی پتو دار","کامیون دربستی کار","سایر خودروهای مبلی"];
    const vehicleType=String(b.vehicle_type??"").trim();
    const plate=String(b.license_plate??"").trim();
    const primaryDestination=String(b.primary_destination??"").trim();
    const destinations=Array.isArray(b.destinations)?b.destinations.map((x:unknown)=>String(x).trim()).filter(Boolean).slice(0,30):[];
    if(vehicleType && !vehicles.includes(vehicleType))return out({error:"نوع خودرو معتبر نیست."},400,origin);
    if(plate.length>30)return out({error:"شماره پلاک بیش از حد طولانی است."},400,origin);
    if(primaryDestination.length>100||destinations.some((x:string)=>x.length>100))return out({error:"نام مقصد بیش از حد طولانی است."},400,origin);
    if(vehicleType)profile.vehicle_type=vehicleType;
    if(plate)profile.license_plate=plate;
    if(primaryDestination)profile.primary_destination=primaryDestination;
    if(destinations.length)profile.destinations=Array.from(new Set([primaryDestination,...destinations].filter(Boolean))).slice(0,30);
    profile.full_name=firstName+" "+lastName;
   }else{
    const companyName=String(b.company_name??"").trim();
    const activityCity=String(b.activity_city??"").trim();
    const officeAddress=String(b.office_address??"").trim();
    if(companyName.length<2||companyName.length>160)return out({error:"نام شرکت باید حداقل ۲ نویسه باشد."},400,origin);
    if(activityCity.length<2||activityCity.length>100)return out({error:"شهر محل فعالیت را کامل وارد کنید."},400,origin);
    if(officeAddress.length>500)return out({error:"نشانی بیش از حد طولانی است."},400,origin);
    profile.company_name=companyName;
    profile.manager_name=firstName+" "+lastName;
    profile.activity_city=activityCity;
    profile.office_address=officeAddress||null;
   }
   const {error:profileError}=await admin.from(table).update(profile).eq("id",currentUser.id);
   if(profileError)return out({error:"ذخیره مشخصات انجام نشد؛ اطلاعات را بررسی کنید."},400,origin);
   const {error:metaError}=await admin.auth.admin.updateUserById(currentUser.id,{user_metadata:{...currentUser.user_metadata,first_name:firstName,last_name:lastName,full_name:firstName+" "+lastName}});
   if(metaError)return out({error:"مشخصات ذخیره شد اما همگام‌سازی حساب کامل نشد."},500,origin);
   if(password){
    const {error:passwordError}=await admin.auth.admin.updateUserById(currentUser.id,{password});
    if(passwordError)return out({error:"مشخصات ذخیره شد اما تغییر رمز انجام نشد."},400,origin);
   }
   return out({message:"مشخصات حساب با موفقیت ذخیره شد."},200,origin);
  }
  if(action==="login"){
   if(!username||!password)return out({error:"نام کاربری/موبایل یا رمز عبور نادرست است."},401,origin);
   const accountType=String(b.account_type??"");
   const mobileDigits=username.replace(/[^۰-۹٠-٩0-9]/g,"").replace(/[۰-۹]/g,(c)=>String("۰۱۲۳۴۵۶۷۸۹".indexOf(c))).replace(/[٠-٩]/g,(c)=>String("٠١٢٣٤٥٦٧٨٩".indexOf(c)));
   const normalizedMobile=mobileDigits.startsWith("0098")?"0"+mobileDigits.slice(4):mobileDigits.startsWith("98")?"0"+mobileDigits.slice(2):mobileDigits.startsWith("9")&&mobileDigits.length===10?"0"+mobileDigits:mobileDigits;
   let userId="";
   if(/^09[0-9]{9}$/.test(normalizedMobile)){
    if(!["driver","company"].includes(accountType))return out({error:"نوع حساب انتخاب‌شده معتبر نیست."},401,origin);
    const table=accountType==="driver"?"drivers":"companies";
    const {data:profile,error:profileError}=await admin.from(table).select("id").eq("mobile",normalizedMobile).maybeSingle();
    if(profileError||!profile?.id)return out({error:"نام کاربری/موبایل یا رمز عبور نادرست است."},401,origin);
    userId=profile.id;
   }else{
    if(!validName(username))return out({error:"نام کاربری/موبایل یا رمز عبور نادرست است."},401,origin);
    const {data:row,error:rowError}=await admin.from("account_usernames").select("user_id").eq("username_normalized",username).maybeSingle();
    if(rowError||!row?.user_id)return out({error:"نام کاربری/موبایل یا رمز عبور نادرست است."},401,origin);
    userId=row.user_id;
   }
   const {data:userResult,error:userError}=await admin.auth.admin.getUserById(userId);
   const account=userResult.user;
   const email=account?.email;
   if(userError||!email||(accountType&&account.app_metadata?.account_type!==accountType))return out({error:"نام کاربری/موبایل یا رمز عبور نادرست است."},401,origin);
   const {data,error}=await auth.auth.signInWithPassword({email,password});
   if(error||!data.session)return out({error:"نام کاربری/موبایل یا رمز عبور نادرست است."},401,origin);
   return out({user:data.user,session:data.session},200,origin);
  }
  if(action==="reset"){
   const identity=String(b.identity??"").trim();let email=identity.includes("@")?identity.toLowerCase():"";
   if(!email){const {data:row}=await admin.from("account_usernames").select("user_id").eq("username_normalized",norm(identity)).maybeSingle();if(row?.user_id){const {data}=await admin.auth.admin.getUserById(row.user_id);email=data.user?.email??"";}}
   if(email.endsWith("@accounts.elambar.invalid"))return out({message:"بازیابی رمز رانندگان و شرکت‌ها از طریق پیامک در مرحله بعد فعال می‌شود. فعلاً با پشتیبانی سامانه تماس بگیرید."},200,origin);
   if(email)await auth.auth.resetPasswordForEmail(email,{redirectTo:"elambar://reset-password"});
   return out({message:"اگر حسابی وجود داشته باشد، پیوند بازیابی به ایمیل ثبت‌شده ارسال می‌شود."},200,origin);
  }
  return out({error:"عملیات شناخته نشد."},400,origin);
 }catch(_){return out({error:"ارتباط با سامانه برقرار نشد. دوباره تلاش کنید."},500,origin);}
});