/*
 * Copyright (C) 2016 Ulrich Raab.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ir.mirrajabi.rxcontacts;


import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import timber.log.Timber;

import static ir.mirrajabi.rxcontacts.ColumnMapper.mapFamilyName;
import static ir.mirrajabi.rxcontacts.ColumnMapper.mapGivenName;


/**
 * Android contacts as rx observable.
 *
 * @author Ulrich Raab
 * @author MADNESS
 */
public class RxContacts {
    private static final String[] PROJECTION = {
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
            ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
            ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
            ContactsContract.CommonDataKinds.Email.DATA,
            ContactsContract.CommonDataKinds.Phone.HAS_PHONE_NUMBER,
            ContactsContract.CommonDataKinds.Phone.NUMBER
    };

    private ContentResolver mResolver;

    public static Observable<List<Contact>> fetch(@NonNull final Context context, final int page) {
        return Observable.create(new ObservableOnSubscribe<List<Contact>>() {
            @Override
            public void subscribe(@io.reactivex.annotations.NonNull
                                          ObservableEmitter<List<Contact>> e) throws Exception {
                e.onNext(new RxContacts(context).fetch(page, null));
//                e.onComplete();
            }
        });
    }

    public static Observable<List<Contact>> search(@NonNull final Context context, final int page, final String query) {
        return Observable.create(new ObservableOnSubscribe<List<Contact>>() {
            @Override
            public void subscribe(@io.reactivex.annotations.NonNull
                                          ObservableEmitter<List<Contact>> e) throws Exception {
                e.onNext(new RxContacts(context).fetch(page, query));
//                e.onComplete();
            }
        });
    }

    /**
     * WARNING: this does not return first and last name
     *
     * @param context
     * @param id
     * @return
     */
    public static Observable<Contact> getContact(@NonNull final Context context, final long id) {
        return Observable.create(new ObservableOnSubscribe<Contact>() {
            @Override
            public void subscribe(@io.reactivex.annotations.NonNull
                                          ObservableEmitter<Contact> e) throws Exception {
                e.onNext(new RxContacts(context).getContact(id));
//                e.onComplete();
            }
        });
    }

    private RxContacts(@NonNull Context context) {
        mResolver = context.getContentResolver();
    }


    private Contact getContact(long id) {
        Timber.i("Getting contact id: %d", id);

        Cursor cursor = mResolver.query(
                ContactsContract.Data.CONTENT_URI,
                PROJECTION,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                new String[]{String.valueOf(id)},
                null
        );


        if (cursor == null || cursor.getCount() == 0 || !cursor.moveToNext()) {
            return null;
        }


        int givenNameColumnIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME);
        int familyNameColumnIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME);
        int hasPhoneNumberColumnIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.HAS_PHONE_NUMBER);


        Contact contact = new Contact(id);
        mapGivenName(cursor, contact, givenNameColumnIndex);
        mapFamilyName(cursor, contact, familyNameColumnIndex);

        // email
        Cursor ce = mResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                null,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                new String[]{Long.toString(id)},
                null
        );

        if (ce != null && ce.moveToFirst()) {
            String email = ce.getString(ce.getColumnIndex(ContactsContract.CommonDataKinds.Email.DATA));
            contact.getEmails().add(email);
            ce.close();
        }


        // phone
        int hasPhone = cursor.getInt(hasPhoneNumberColumnIndex);
        if (hasPhone > 0) {
            Cursor cp = mResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    null,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                    new String[]{Long.toString(id)},
                    null
            );
            if (cp != null && cp.moveToFirst()) {
                String phone = cp.getString(cp.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                if (!TextUtils.isEmpty(phone)) {
                    contact.getPhoneNumbers().add(phone);
                }
                cp.close();
            }
        }

        cursor.close();

        return contact;
    }

    private List<Contact> fetch(int page, String query) {
        HashMap<Long, Contact> contacts = new HashMap<>();
        Cursor cursor = createCursor(page, query);
        Timber.i("Query finished: %d results", cursor.getCount());
        cursor.moveToFirst();

        List<Contact> all = new ArrayList<>();
        while (!cursor.isAfterLast()) {

            int idColumnIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID);
            int givenNameColumnIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME);
            int familyNameColumnIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME);

            long id = cursor.getLong(idColumnIndex);

            Contact contact;

            if (contacts.containsKey(id)) {
                contact = contacts.get(id);
            } else {
                contact = new Contact(id);
                mapGivenName(cursor, contact, givenNameColumnIndex);
                mapFamilyName(cursor, contact, familyNameColumnIndex);
                contacts.put(id, contact);
            }

            cursor.moveToNext();
        }
        cursor.close();

        for (Long key : contacts.keySet()) {
            all.add(contacts.get(key));
        }

        return all;
    }

    private Cursor createCursor(int page, String query) {
        String whereName = ContactsContract.Data.MIMETYPE + " = ?";
        whereName += " AND " + ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME + " IS NOT NULL";
        whereName += " AND LENGTH(" + ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME + ") > 0";
        if (query != null && query.length() > 0) {
            whereName += " AND " + ContactsContract.Data.DISPLAY_NAME + " LIKE ?";
        }

        String[] whereNameParams;
        if (query != null && query.length() > 0) {
            whereNameParams = new String[]{ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, "%" + query + "%"};
        } else {
            whereNameParams = new String[]{ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE};
        }

        String limit = String.format(" LIMIT %d,%d", (page - 1) * 50, 50 * page);


        Timber.i("Fetching contacts with limit: %s", limit);

        Timber.v("where: %s", whereName);

        return mResolver.query(
                ContactsContract.Data.CONTENT_URI,
                PROJECTION,
                whereName,
                whereNameParams,
                ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME
        );
    }
}
